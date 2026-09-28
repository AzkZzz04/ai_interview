package dev.jiaming.ai_interview.rag

import dev.jiaming.ai_interview.document.DocumentSourceType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.Optional
import java.util.OptionalLong
import java.util.UUID

@Repository
class RagDocumentIndexRepository(private val jdbcTemplate: JdbcTemplate) {
    fun insertClaim(indexId: UUID, identity: RagDocumentIndexIdentity, now: Instant): Boolean = jdbcTemplate.update(
        """INSERT INTO ai_interview_app.rag_document_indexes (
            index_id, source_type, content_hash, embedding_model, embedding_dimensions,
            chunk_schema, status, claim_version, indexing_started_at,
            document_count, created_at, updated_at, last_used_at
        ) VALUES (?, ?, ?, ?, ?, ?, 'INDEXING', 1, ?, 0, ?, ?, ?)
        ON CONFLICT (source_type, content_hash, embedding_model, embedding_dimensions, chunk_schema) DO NOTHING""",
        indexId, identity.sourceType.name, identity.contentHash, identity.embeddingModel, identity.embeddingDimensions,
        identity.chunkSchema, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
    ) == 1

    fun find(identity: RagDocumentIndexIdentity): Optional<RagDocumentIndex> = jdbcTemplate.query(
        """SELECT index_id, source_type, content_hash, embedding_model, embedding_dimensions, chunk_schema, status,
            claim_version, indexing_started_at, document_count, updated_at, last_used_at
            FROM ai_interview_app.rag_document_indexes WHERE source_type = ? AND content_hash = ? AND embedding_model = ?
            AND embedding_dimensions = ? AND chunk_schema = ?""",
        { rs, _ -> map(rs) }, identity.sourceType.name, identity.contentHash, identity.embeddingModel,
        identity.embeddingDimensions, identity.chunkSchema
    ).stream().findFirst()

    fun touchReady(indexId: UUID, claimVersion: Long, now: Instant) = jdbcTemplate.update(
        "UPDATE ai_interview_app.rag_document_indexes SET last_used_at = ?, updated_at = ? WHERE index_id = ? AND claim_version = ? AND status = 'READY'",
        Timestamp.from(now), Timestamp.from(now), indexId, claimVersion
    ) == 1

    fun takeOver(current: RagDocumentIndex, now: Instant, indexingCutoff: Instant, failedCutoff: Instant) = jdbcTemplate.update(
        """UPDATE ai_interview_app.rag_document_indexes SET status = 'INDEXING', claim_version = claim_version + 1,
            indexing_started_at = ?, document_count = 0, last_error = NULL, updated_at = ?, last_used_at = ?
            WHERE index_id = ? AND claim_version = ? AND ((status = 'INDEXING' AND indexing_started_at < ?)
            OR (status = 'FAILED' AND updated_at < ?))""",
        Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), current.indexId, current.claimVersion,
        Timestamp.from(indexingCutoff), Timestamp.from(failedCutoff)
    ) == 1

    fun markReady(indexId: UUID, claimVersion: Long, documentCount: Int, now: Instant) = jdbcTemplate.update(
        """UPDATE ai_interview_app.rag_document_indexes SET status = 'READY', document_count = ?, last_error = NULL,
            updated_at = ?, last_used_at = ? WHERE index_id = ? AND claim_version = ? AND status = 'INDEXING'""",
        documentCount, Timestamp.from(now), Timestamp.from(now), indexId, claimVersion
    ) == 1

    fun markFailed(indexId: UUID, claimVersion: Long, errorCode: String, now: Instant) = jdbcTemplate.update(
        "UPDATE ai_interview_app.rag_document_indexes SET status = 'FAILED', last_error = ?, updated_at = ? WHERE index_id = ? AND claim_version = ? AND status = 'INDEXING'",
        errorCode, Timestamp.from(now), indexId, claimVersion
    ) == 1

    fun cleanupCandidates(cutoff: Instant, deletingCutoff: Instant, limit: Int): List<RagDocumentIndex> = jdbcTemplate.query(
        """SELECT index_id, source_type, content_hash, embedding_model, embedding_dimensions, chunk_schema, status,
            claim_version, indexing_started_at, document_count, updated_at, last_used_at
            FROM ai_interview_app.rag_document_indexes WHERE (status IN ('READY', 'FAILED') AND last_used_at < ?)
            OR (status = 'DELETING' AND updated_at < ?) ORDER BY last_used_at LIMIT ?""",
        { rs, _ -> map(rs) }, Timestamp.from(cutoff), Timestamp.from(deletingCutoff), limit
    )

    fun claimDeleting(index: RagDocumentIndex, cutoff: Instant, deletingCutoff: Instant, now: Instant): OptionalLong {
        val claims = jdbcTemplate.query(
            """UPDATE ai_interview_app.rag_document_indexes SET status = 'DELETING', claim_version = claim_version + 1, updated_at = ?
                WHERE index_id = ? AND claim_version = ? AND ((status IN ('READY', 'FAILED') AND last_used_at < ?)
                OR (status = 'DELETING' AND updated_at < ?)) RETURNING claim_version""",
            { rs, _ -> rs.getLong("claim_version") }, Timestamp.from(now), index.indexId, index.claimVersion,
            Timestamp.from(cutoff), Timestamp.from(deletingCutoff)
        )
        return if (claims.isEmpty()) OptionalLong.empty() else OptionalLong.of(claims.first())
    }

    fun deleteClaimed(indexId: UUID, claimVersion: Long) {
        jdbcTemplate.update("DELETE FROM ai_interview_app.rag_document_indexes WHERE index_id = ? AND claim_version = ? AND status = 'DELETING'", indexId, claimVersion)
    }

    fun restoreDeleteFailure(indexId: UUID, claimVersion: Long, now: Instant) {
        jdbcTemplate.update("""UPDATE ai_interview_app.rag_document_indexes SET status = 'FAILED', last_error = 'VECTOR_DELETE_FAILED', updated_at = ?
            WHERE index_id = ? AND claim_version = ? AND status = 'DELETING'""", Timestamp.from(now), indexId, claimVersion)
    }

    private fun map(rs: ResultSet): RagDocumentIndex {
        val startedAt = rs.getTimestamp("indexing_started_at")
        return RagDocumentIndex(
            rs.getObject("index_id", UUID::class.java),
            RagDocumentIndexIdentity(DocumentSourceType.valueOf(rs.getString("source_type")), rs.getString("content_hash"),
                rs.getString("embedding_model"), rs.getInt("embedding_dimensions"), rs.getString("chunk_schema")),
            RagDocumentIndexStatus.valueOf(rs.getString("status")), rs.getLong("claim_version"), startedAt?.toInstant(),
            rs.getInt("document_count"), rs.getTimestamp("updated_at").toInstant(), rs.getTimestamp("last_used_at").toInstant()
        )
    }
}
