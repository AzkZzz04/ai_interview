package dev.jiaming.ai_interview.rag;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import dev.jiaming.ai_interview.document.DocumentSourceType;

@Repository
class RagDocumentIndexRepository {

	private final JdbcTemplate jdbcTemplate;

	RagDocumentIndexRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	boolean insertClaim(UUID indexId, RagDocumentIndexIdentity identity, Instant now) {
		return jdbcTemplate.update(
			"""
				INSERT INTO ai_interview_app.rag_document_indexes (
				    index_id, source_type, content_hash, embedding_model, embedding_dimensions,
				    chunk_schema, status, claim_version, indexing_started_at,
				    document_count, created_at, updated_at, last_used_at
				)
				VALUES (?, ?, ?, ?, ?, ?, 'INDEXING', 1, ?, 0, ?, ?, ?)
				ON CONFLICT (source_type, content_hash, embedding_model, embedding_dimensions, chunk_schema)
				DO NOTHING
				""",
			indexId,
			identity.sourceType().name(),
			identity.contentHash(),
			identity.embeddingModel(),
			identity.embeddingDimensions(),
			identity.chunkSchema(),
			Timestamp.from(now),
			Timestamp.from(now),
			Timestamp.from(now),
			Timestamp.from(now)
		) == 1;
	}

	Optional<RagDocumentIndex> find(RagDocumentIndexIdentity identity) {
		return jdbcTemplate.query(
			"""
				SELECT index_id, source_type, content_hash, embedding_model, embedding_dimensions,
				       chunk_schema, status, claim_version, indexing_started_at, document_count,
				       updated_at, last_used_at
				FROM ai_interview_app.rag_document_indexes
				WHERE source_type = ?
				  AND content_hash = ?
				  AND embedding_model = ?
				  AND embedding_dimensions = ?
				  AND chunk_schema = ?
				""",
			this::map,
			identity.sourceType().name(),
			identity.contentHash(),
			identity.embeddingModel(),
			identity.embeddingDimensions(),
			identity.chunkSchema()
		).stream().findFirst();
	}

	boolean touchReady(UUID indexId, long claimVersion, Instant now) {
		return jdbcTemplate.update(
			"""
				UPDATE ai_interview_app.rag_document_indexes
				SET last_used_at = ?, updated_at = ?
				WHERE index_id = ? AND claim_version = ? AND status = 'READY'
				""",
			Timestamp.from(now), Timestamp.from(now), indexId, claimVersion
		) == 1;
	}

	boolean takeOver(RagDocumentIndex current, Instant now, Instant indexingCutoff, Instant failedCutoff) {
		return jdbcTemplate.update(
			"""
				UPDATE ai_interview_app.rag_document_indexes
				SET status = 'INDEXING',
				    claim_version = claim_version + 1,
				    indexing_started_at = ?,
				    document_count = 0,
				    last_error = NULL,
				    updated_at = ?,
				    last_used_at = ?
				WHERE index_id = ? AND claim_version = ?
				  AND (
				      (status = 'INDEXING' AND indexing_started_at < ?)
				      OR (status = 'FAILED' AND updated_at < ?)
				  )
				""",
			Timestamp.from(now),
			Timestamp.from(now),
			Timestamp.from(now),
			current.indexId(),
			current.claimVersion(),
			Timestamp.from(indexingCutoff),
			Timestamp.from(failedCutoff)
		) == 1;
	}

	boolean markReady(UUID indexId, long claimVersion, int documentCount, Instant now) {
		return jdbcTemplate.update(
			"""
				UPDATE ai_interview_app.rag_document_indexes
				SET status = 'READY', document_count = ?, last_error = NULL,
				    updated_at = ?, last_used_at = ?
				WHERE index_id = ? AND claim_version = ? AND status = 'INDEXING'
				""",
			documentCount, Timestamp.from(now), Timestamp.from(now), indexId, claimVersion
		) == 1;
	}

	boolean markFailed(UUID indexId, long claimVersion, String errorCode, Instant now) {
		return jdbcTemplate.update(
			"""
				UPDATE ai_interview_app.rag_document_indexes
				SET status = 'FAILED', last_error = ?, updated_at = ?
				WHERE index_id = ? AND claim_version = ? AND status = 'INDEXING'
				""",
			errorCode, Timestamp.from(now), indexId, claimVersion
		) == 1;
	}

	List<RagDocumentIndex> cleanupCandidates(Instant cutoff, Instant deletingCutoff, int limit) {
		return jdbcTemplate.query(
			"""
				SELECT index_id, source_type, content_hash, embedding_model, embedding_dimensions,
				       chunk_schema, status, claim_version, indexing_started_at, document_count,
				       updated_at, last_used_at
				FROM ai_interview_app.rag_document_indexes
				WHERE (status IN ('READY', 'FAILED') AND last_used_at < ?)
				   OR (status = 'DELETING' AND updated_at < ?)
				ORDER BY last_used_at
				LIMIT ?
				""",
			this::map,
			Timestamp.from(cutoff), Timestamp.from(deletingCutoff), limit
		);
	}

	OptionalLong claimDeleting(
		RagDocumentIndex index,
		Instant cutoff,
		Instant deletingCutoff,
		Instant now
	) {
		List<Long> claims = jdbcTemplate.query(
			"""
				UPDATE ai_interview_app.rag_document_indexes
				SET status = 'DELETING', claim_version = claim_version + 1, updated_at = ?
				WHERE index_id = ? AND claim_version = ?
				  AND (
				      (status IN ('READY', 'FAILED') AND last_used_at < ?)
				      OR (status = 'DELETING' AND updated_at < ?)
				  )
				RETURNING claim_version
				""",
			(rs, rowNum) -> rs.getLong("claim_version"),
			Timestamp.from(now),
			index.indexId(),
			index.claimVersion(),
			Timestamp.from(cutoff),
			Timestamp.from(deletingCutoff)
		);
		return claims.isEmpty() ? OptionalLong.empty() : OptionalLong.of(claims.getFirst());
	}

	void deleteClaimed(UUID indexId, long claimVersion) {
		jdbcTemplate.update(
			"DELETE FROM ai_interview_app.rag_document_indexes WHERE index_id = ? AND claim_version = ? AND status = 'DELETING'",
			indexId,
			claimVersion
		);
	}

	void restoreDeleteFailure(UUID indexId, long claimVersion, Instant now) {
		jdbcTemplate.update(
			"""
				UPDATE ai_interview_app.rag_document_indexes
				SET status = 'FAILED', last_error = 'VECTOR_DELETE_FAILED', updated_at = ?
				WHERE index_id = ? AND claim_version = ? AND status = 'DELETING'
				""",
			Timestamp.from(now), indexId, claimVersion
		);
	}

	private RagDocumentIndex map(ResultSet resultSet, int rowNumber) throws SQLException {
		Timestamp startedAt = resultSet.getTimestamp("indexing_started_at");
		return new RagDocumentIndex(
			resultSet.getObject("index_id", UUID.class),
			new RagDocumentIndexIdentity(
				DocumentSourceType.valueOf(resultSet.getString("source_type")),
				resultSet.getString("content_hash"),
				resultSet.getString("embedding_model"),
				resultSet.getInt("embedding_dimensions"),
				resultSet.getString("chunk_schema")
			),
			RagDocumentIndexStatus.valueOf(resultSet.getString("status")),
			resultSet.getLong("claim_version"),
			startedAt == null ? null : startedAt.toInstant(),
			resultSet.getInt("document_count"),
			resultSet.getTimestamp("updated_at").toInstant(),
			resultSet.getTimestamp("last_used_at").toInstant()
		);
	}
}
