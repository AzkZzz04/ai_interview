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
    fun save(userId: UUID, jobDescription: String?): Optional<UUID> {
        if (jobDescription.isNullOrBlank()) return Optional.empty()
        if (normalizer.normalize(jobDescription).isBlank()) return Optional.empty()
        return Optional.of(findOrCreateDocument(userId, jobDescription).resourceId())
    }

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
                ORDER BY created_at DESC
                LIMIT 1
            """.trimIndent(),
            userId,
            contentHash,
            normalizedText,
        )

    @Transactional
    fun findOrCreateDocument(userId: UUID, jobDescription: String): ResolvedDocument {
        val normalizedText = normalizer.normalize(jobDescription)
        if (normalizedText.isBlank()) throw IllegalArgumentException("Job description text is required")
        val contentHash = contentHasher.sha256(normalizedText)
        val existing = findDocumentByContent(userId, contentHash, normalizedText)
        if (existing.isPresent) return existing.get()

        val jobDescriptionId = UUID.randomUUID()
        jdbcTemplate.update(
            """
                INSERT INTO ai_interview_app.job_descriptions (
                    id, user_id, raw_text, normalized_text, content_hash, parsed_requirements
                )
                VALUES (?, ?, ?, ?, ?, '[]'::jsonb)
            """.trimIndent(),
            jobDescriptionId,
            userId,
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
        return findDocument(userId, jobDescriptionId).orElseThrow()
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
