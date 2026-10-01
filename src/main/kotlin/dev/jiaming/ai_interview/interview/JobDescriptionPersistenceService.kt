package dev.jiaming.ai_interview.interview

import dev.jiaming.ai_interview.common.ContentHasher
import dev.jiaming.ai_interview.document.DocumentChunk
import dev.jiaming.ai_interview.document.DocumentSourceType
import dev.jiaming.ai_interview.document.ResolvedDocument
import dev.jiaming.ai_interview.rag.RagContextId
import dev.jiaming.ai_interview.resume.ResumeTextNormalizer
import dev.jiaming.ai_interview.resume.SectionAwareTextChunker
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.Optional
import java.util.UUID

@Service
class JobDescriptionPersistenceService(
    private val jdbcTemplate: JdbcTemplate,
    private val normalizer: ResumeTextNormalizer,
    private val chunker: SectionAwareTextChunker,
    private val contentHasher: ContentHasher,
) {
    @Transactional
    fun findOrCreateTargetJob(userId: UUID, name: String, jobDescription: String): TargetJobDocumentSave =
        findOrCreate(userId, jobDescription, name)

    fun findDocument(userId: UUID, jobDescriptionId: UUID): Optional<ResolvedDocument> = queryDocument(
        """
            SELECT id, normalized_text, content_hash
            FROM ai_interview_app.job_descriptions
            WHERE id = ? AND user_id = ?
        """.trimIndent(),
        jobDescriptionId,
        userId,
    )

    fun findDocumentByContent(userId: UUID, contentHash: String, normalizedText: String): Optional<ResolvedDocument> =
        queryDocument(
            """
                SELECT id, normalized_text, content_hash
                FROM ai_interview_app.job_descriptions
                WHERE user_id = ? AND content_hash = ? AND normalized_text = ?
                ORDER BY created_at, id
                LIMIT 1
            """.trimIndent(),
            userId,
            contentHash,
            normalizedText,
        )

    private fun findOrCreate(userId: UUID, jobDescription: String, name: String): TargetJobDocumentSave {
        val normalizedText = normalizer.normalize(jobDescription)
        if (normalizedText.isBlank()) throw IllegalArgumentException("Job description text is required")
        val contentHash = contentHasher.sha256(normalizedText)
        lockOwner(userId)
        val existing = findDocumentByContent(userId, contentHash, normalizedText)
        if (existing.isPresent) return TargetJobDocumentSave(existing.get(), false)

        val jobDescriptionId = UUID.randomUUID()
        jdbcTemplate.update(
            """
                INSERT INTO ai_interview_app.job_descriptions (
                    id, user_id, name, raw_text, normalized_text, content_hash, parsed_requirements
                )
                VALUES (?, ?, ?, ?, ?, ?, '[]'::jsonb)
            """.trimIndent(),
            jobDescriptionId,
            userId,
            name,
            jobDescription,
            normalizedText,
            contentHash,
        )
        chunker.chunk(normalizedText).forEach { chunk ->
            jdbcTemplate.update(
                """
                    INSERT INTO ai_interview_app.job_description_chunks (
                        id, job_description_id, chunk_index, section, content, metadata
                    )
                    VALUES (?, ?, ?, ?, ?, jsonb_build_object('sourceType', 'job_description', 'contextId', ?))
                """.trimIndent(),
                UUID.randomUUID(),
                jobDescriptionId,
                chunk.index,
                chunk.section,
                chunk.content,
                RagContextId.forChunk("job_description", chunk.section, chunk.index),
            )
        }
        return TargetJobDocumentSave(findDocument(userId, jobDescriptionId).orElseThrow(), true)
    }

    private fun lockOwner(userId: UUID) {
        jdbcTemplate.query(
            "SELECT id FROM ai_interview_app.app_users WHERE id = ? FOR UPDATE",
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            userId,
        )
    }

    private fun queryDocument(sql: String, vararg arguments: Any): Optional<ResolvedDocument> =
        jdbcTemplate.query(sql, { rs, _ ->
            val id = rs.getObject("id", UUID::class.java)
            val normalizedText = rs.getString("normalized_text")
            val storedHash = rs.getString("content_hash")
            ResolvedDocument(
                DocumentSourceType.JOB_DESCRIPTION,
                id,
                storedHash ?: contentHasher.sha256(normalizedText),
                normalizedText,
                findChunks(id),
            )
        }, *arguments).stream().findFirst()

    private fun findChunks(jobDescriptionId: UUID): List<DocumentChunk> = jdbcTemplate.query(
        """
            SELECT chunk_index, section, content
            FROM ai_interview_app.job_description_chunks
            WHERE job_description_id = ?
            ORDER BY chunk_index
        """.trimIndent(),
        { rs, _ ->
            DocumentChunk(
                rs.getInt("chunk_index"),
                rs.getString("section"),
                rs.getString("content"),
                RagContextId.forChunk("job_description", rs.getString("section"), rs.getInt("chunk_index")),
            )
        },
        jobDescriptionId,
    )

}

data class TargetJobDocumentSave(val document: ResolvedDocument, val created: Boolean)
