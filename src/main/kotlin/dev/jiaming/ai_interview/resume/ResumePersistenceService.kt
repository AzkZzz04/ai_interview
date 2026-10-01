package dev.jiaming.ai_interview.resume

import dev.jiaming.ai_interview.common.ContentHasher
import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.document.DocumentChunk
import dev.jiaming.ai_interview.document.DocumentSourceType
import dev.jiaming.ai_interview.document.ResolvedDocument
import dev.jiaming.ai_interview.rag.RagContextId
import java.nio.charset.StandardCharsets
import java.util.Optional
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ResumePersistenceService(
    private val jdbcTemplate: JdbcTemplate,
    private val localUserService: LocalUserService,
    private val chunker: SectionAwareTextChunker,
    private val contentHasher: ContentHasher
) {
    fun createPending(
        userId: UUID, originalFilename: String?, contentType: String?, detectedContentType: String?, sizeBytes: Long,
        storageKey: String?, name: String, jobTitle: String?, fileHash: String?
    ): Optional<UUID> {
        val resumeId = UUID.randomUUID()
        val inserted = jdbcTemplate.query(
            """
                INSERT INTO ai_interview_app.resumes (
                    id, user_id, original_filename, content_type, detected_content_type,
                    size_bytes, storage_key, raw_text, normalized_text, parsed_skills,
                    processing_status, failure_code, failure_message, name, job_title, source, file_hash, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, '[]'::jsonb, 'PENDING', NULL, NULL, ?, ?, 'UPLOAD', ?, now())
                ON CONFLICT (user_id, file_hash) WHERE file_hash IS NOT NULL DO NOTHING
                RETURNING id
                """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            resumeId, userId, originalFilename, contentType, detectedContentType, sizeBytes, storageKey, name, jobTitle, fileHash
        )
        return Optional.ofNullable(inserted.firstOrNull())
    }

    /** Called while the extraction job row is locked by JobEffectMaterializationService. */
    @Transactional
    fun completeExtractionForJob(
        resumeId: UUID, storageKey: String, rawText: String, normalizedText: String, chunks: List<ResumeChunkResponse>
    ): ResumeExtractionResult {
        val userId = localUserService.localUserId()
        lockOwner(userId)
        val contentHash = contentHasher.sha256(normalizedText)
        val duplicate = jdbcTemplate.query(
            """
                SELECT id, name FROM ai_interview_app.resumes
                WHERE user_id = ? AND id <> ? AND processing_status = 'READY'
                  AND content_hash = ? AND normalized_text = ?
                ORDER BY created_at DESC LIMIT 1
                """.trimIndent(),
            RowMapper { rs, _ -> DuplicateResume(rs.getObject("id", UUID::class.java).toString(), rs.getString("name")) },
            userId, resumeId, contentHash, normalizedText
        ).firstOrNull()
        if (duplicate != null) {
            enqueueStorageCleanup(storageKey)
            jdbcTemplate.update("DELETE FROM ai_interview_app.resumes WHERE id = ? AND user_id = ?", resumeId, userId)
            return ResumeExtractionResult(resumeId.toString(), duplicate)
        }

        val updated = jdbcTemplate.update(
            """
                UPDATE ai_interview_app.resumes
                SET raw_text = ?, normalized_text = ?, processing_status = 'READY',
                    failure_code = NULL, failure_message = NULL, content_hash = ?, updated_at = now()
                WHERE id = ? AND user_id = ? AND processing_status IN ('PENDING', 'READY')
                """.trimIndent(),
            rawText, normalizedText, contentHash, resumeId, userId
        )
        if (updated != 1) throw IllegalStateException("Pending resume does not exist: $resumeId")
        replaceChunks(resumeId, chunks)
        return ResumeExtractionResult(resumeId.toString(), null)
    }

    @Transactional
    fun createPaste(userId: UUID, name: String, jobTitle: String?, rawText: String, normalizedText: String): PastePersistenceResult {
        lockOwner(userId)
        val contentHash = contentHasher.sha256(normalizedText)
        val duplicate = jdbcTemplate.query(
            """
                SELECT id FROM ai_interview_app.resumes
                WHERE user_id = ? AND processing_status = 'READY'
                  AND content_hash = ? AND normalized_text = ?
                ORDER BY created_at DESC LIMIT 1
                """.trimIndent(),
            RowMapper { rs, _ -> rs.getObject("id", UUID::class.java) }, userId, contentHash, normalizedText
        ).firstOrNull()
        if (duplicate != null) return PastePersistenceResult(duplicate, true)

        val resumeId = UUID.randomUUID()
        jdbcTemplate.update(
            """
                INSERT INTO ai_interview_app.resumes (
                    id, user_id, original_filename, content_type, detected_content_type, size_bytes,
                    raw_text, normalized_text, content_hash, parsed_skills, processing_status,
                    name, job_title, source, updated_at
                )
                VALUES (?, ?, NULL, 'text/plain', 'text/plain', ?, ?, ?, ?, '[]'::jsonb, 'READY', ?, ?, 'PASTE', now())
                """.trimIndent(),
            resumeId, userId, normalizedText.toByteArray(StandardCharsets.UTF_8).size, rawText, normalizedText,
            contentHash, name, jobTitle
        )
        insertChunks(resumeId, normalizedText)
        return PastePersistenceResult(resumeId, false)
    }

    fun enqueueStorageCleanup(storageKey: String) {
        jdbcTemplate.update(
            "INSERT INTO ai_interview_app.storage_cleanup (storage_key) VALUES (?) ON CONFLICT (storage_key) DO NOTHING",
            storageKey
        )
    }

    fun findPendingStorageCleanup(limit: Int): List<String> = jdbcTemplate.query(
        "SELECT storage_key FROM ai_interview_app.storage_cleanup ORDER BY created_at LIMIT ?",
        RowMapper { rs, _ -> rs.getString("storage_key") }, limit
    )

    fun acknowledgeStorageCleanup(storageKey: String) {
        jdbcTemplate.update("DELETE FROM ai_interview_app.storage_cleanup WHERE storage_key = ?", storageKey)
    }

    fun deletePending(resumeId: UUID) {
        jdbcTemplate.update("DELETE FROM ai_interview_app.resumes WHERE id = ? AND processing_status = 'PENDING'", resumeId)
    }

    fun markFailed(resumeId: UUID, errorCode: String?, errorMessage: String?): Optional<String> {
        jdbcTemplate.update(
            """
                UPDATE ai_interview_app.resumes
                SET processing_status = 'FAILED', failure_code = ?, failure_message = ?, updated_at = now()
                WHERE id = ? AND processing_status IN ('PENDING', 'READY', 'FAILED')
                """.trimIndent(),
            errorCode, truncate(errorMessage), resumeId
        )
        return findStorageKey(resumeId)
    }

    fun findFailedStorageObjects(limit: Int): List<FailedResumeStorage> = jdbcTemplate.query(
        """
            SELECT id, storage_key
            FROM ai_interview_app.resumes
            WHERE processing_status = 'FAILED' AND storage_key IS NOT NULL AND btrim(storage_key) <> ''
            ORDER BY updated_at
            LIMIT ?
            """.trimIndent(),
        RowMapper { rs, _ -> FailedResumeStorage(rs.getObject("id", UUID::class.java), rs.getString("storage_key")) },
        limit
    )

    fun findUnappliedTerminalFailures(limit: Int): List<FailedResumeJob> = jdbcTemplate.query(
        """
            SELECT r.id, j.error_code, j.last_error
            FROM ai_interview_app.resumes r
            JOIN ai_interview_app.background_jobs j
              ON j.resource_id = r.id AND j.job_type = 'RESUME_EXTRACTION'
            WHERE j.status = 'FAILED' AND r.processing_status <> 'FAILED'
            ORDER BY j.completed_at
            LIMIT ?
            """.trimIndent(),
        RowMapper { rs, _ -> FailedResumeJob(rs.getObject("id", UUID::class.java), rs.getString("error_code"), rs.getString("last_error")) },
        limit
    )

    fun clearStorageKey(resumeId: UUID, expectedStorageKey: String): Boolean = jdbcTemplate.update(
        """
            UPDATE ai_interview_app.resumes
            SET storage_key = NULL, updated_at = now()
            WHERE id = ? AND processing_status = 'FAILED' AND storage_key = ?
            """.trimIndent(), resumeId, expectedStorageKey
    ) == 1

    fun findProcessingStatus(userId: UUID, resumeId: UUID): Optional<String> = jdbcTemplate.query(
        "SELECT processing_status FROM ai_interview_app.resumes WHERE id = ? AND user_id = ?",
        RowMapper { rs, _ -> rs.getString("processing_status") }, resumeId, userId
    ).stream().findFirst()

    fun findReadyDocument(userId: UUID, resumeId: UUID): Optional<ResolvedDocument> = queryDocument(
        """
            SELECT id, normalized_text, content_hash
            FROM ai_interview_app.resumes
            WHERE id = ? AND user_id = ? AND processing_status = 'READY'
              AND normalized_text IS NOT NULL AND btrim(normalized_text) <> ''
            """.trimIndent(), resumeId, userId
    )

    fun findChunks(resumeId: UUID): List<ResumeChunkResponse> = jdbcTemplate.query(
        """
            SELECT chunk_index, section, content FROM ai_interview_app.resume_chunks
            WHERE resume_id = ? ORDER BY chunk_index
            """.trimIndent(),
        RowMapper { rs, _ ->
            val content = rs.getString("content")
            ResumeChunkResponse(rs.getInt("chunk_index"), rs.getString("section"), content, content.length)
        }, resumeId
    )

    private fun queryDocument(sql: String, vararg arguments: Any?): Optional<ResolvedDocument> {
        val documents = jdbcTemplate.query(sql, RowMapper { rs, _ ->
            val id = rs.getObject("id", UUID::class.java)
            val normalizedText = rs.getString("normalized_text")
            val storedHash = rs.getString("content_hash")
            val contentHash = storedHash ?: contentHasher.sha256(normalizedText)
            ResolvedDocument(DocumentSourceType.RESUME, id, contentHash, normalizedText, findDocumentChunks(id))
        }, *arguments)
        return documents.stream().findFirst()
    }

    private fun findDocumentChunks(resumeId: UUID): List<DocumentChunk> = findChunks(resumeId).map { chunk ->
        DocumentChunk(chunk.index, chunk.section, chunk.content, RagContextId.forChunk("resume", chunk.section, chunk.index))
    }

    private fun insertChunks(resumeId: UUID, normalizedText: String) {
        for (chunk in chunker.chunk(normalizedText)) {
            jdbcTemplate.update(
                """
                    INSERT INTO ai_interview_app.resume_chunks (id, resume_id, chunk_index, section, content, metadata)
                    VALUES (?, ?, ?, ?, ?, jsonb_build_object('sourceType', 'resume', 'contextId', ?))
                    """.trimIndent(),
                UUID.randomUUID(), resumeId, chunk.index, chunk.section, chunk.content, RagContextId.forChunk("resume", chunk)
            )
        }
    }

    private fun replaceChunks(resumeId: UUID, chunks: List<ResumeChunkResponse>) {
        jdbcTemplate.update("DELETE FROM ai_interview_app.resume_chunks WHERE resume_id = ?", resumeId)
        for (chunk in chunks) {
            jdbcTemplate.update(
                """
                    INSERT INTO ai_interview_app.resume_chunks (id, resume_id, chunk_index, section, content, metadata)
                    VALUES (?, ?, ?, ?, ?, jsonb_build_object('sourceType', 'resume', 'contextId', ?))
                    """.trimIndent(),
                UUID.randomUUID(), resumeId, chunk.index, chunk.section, chunk.content,
                RagContextId.forChunk("resume", chunk.section, chunk.index)
            )
        }
    }

    private fun lockOwner(userId: UUID) {
        val owner = jdbcTemplate.query(
            "SELECT id FROM ai_interview_app.app_users WHERE id = ? FOR UPDATE",
            RowMapper { rs, _ -> rs.getObject("id", UUID::class.java) }, userId
        )
        if (owner.isEmpty()) throw IllegalStateException("Resume owner does not exist")
    }

    private fun findStorageKey(resumeId: UUID): Optional<String> = jdbcTemplate.query(
        "SELECT storage_key FROM ai_interview_app.resumes WHERE id = ?",
        RowMapper { rs, _ -> rs.getString("storage_key") }, resumeId
    ).stream().filter { !it.isNullOrBlank() }.findFirst()

    private fun truncate(value: String?): String? = if (value == null || value.length <= 4_000) value else value.substring(0, 4_000)
}
