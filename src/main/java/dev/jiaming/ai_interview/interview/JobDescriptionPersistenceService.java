package dev.jiaming.ai_interview.interview;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.jiaming.ai_interview.common.ContentHasher;
import dev.jiaming.ai_interview.document.DocumentChunk;
import dev.jiaming.ai_interview.document.DocumentSourceType;
import dev.jiaming.ai_interview.document.ResolvedDocument;
import dev.jiaming.ai_interview.rag.RagContextId;
import dev.jiaming.ai_interview.resume.ResumeTextNormalizer;
import dev.jiaming.ai_interview.resume.SectionAwareTextChunker;

@Service
public class JobDescriptionPersistenceService {

	private final JdbcTemplate jdbcTemplate;

	private final ResumeTextNormalizer normalizer;

	private final SectionAwareTextChunker chunker;

	private final ContentHasher contentHasher;

	public JobDescriptionPersistenceService(
		JdbcTemplate jdbcTemplate,
		ResumeTextNormalizer normalizer,
		SectionAwareTextChunker chunker,
		ContentHasher contentHasher
	) {
		this.jdbcTemplate = jdbcTemplate;
		this.normalizer = normalizer;
		this.chunker = chunker;
		this.contentHasher = contentHasher;
	}

	@Transactional
	public Optional<UUID> save(UUID userId, String jobDescription) {
		if (jobDescription == null || jobDescription.isBlank()) {
			return Optional.empty();
		}
		String normalizedText = normalizer.normalize(jobDescription);
		if (normalizedText.isBlank()) {
			return Optional.empty();
		}

		return Optional.of(findOrCreateDocument(userId, jobDescription).resourceId());
	}

	public Optional<ResolvedDocument> findDocument(UUID userId, UUID jobDescriptionId) {
		return queryDocument(
			"""
				SELECT id, normalized_text, content_hash
				FROM ai_interview_app.job_descriptions
				WHERE id = ? AND user_id = ?
				""",
			jobDescriptionId,
			userId
		);
	}

	public Optional<ResolvedDocument> findDocumentByContent(
		UUID userId,
		String contentHash,
		String normalizedText
	) {
		return queryDocument(
			"""
				SELECT id, normalized_text, content_hash
				FROM ai_interview_app.job_descriptions
				WHERE user_id = ? AND content_hash = ? AND normalized_text = ?
				ORDER BY created_at DESC
				LIMIT 1
				""",
			userId,
			contentHash,
			normalizedText
		);
	}

	@Transactional
	public ResolvedDocument findOrCreateDocument(UUID userId, String jobDescription) {
		String normalizedText = normalizer.normalize(jobDescription);
		if (normalizedText.isBlank()) {
			throw new IllegalArgumentException("Job description text is required");
		}
		String contentHash = contentHasher.sha256(normalizedText);
		Optional<ResolvedDocument> existing = findDocumentByContent(userId, contentHash, normalizedText);
		if (existing.isPresent()) {
			return existing.get();
		}

		UUID jobDescriptionId = UUID.randomUUID();
		jdbcTemplate.update(
			"""
				INSERT INTO ai_interview_app.job_descriptions (
					id, user_id, raw_text, normalized_text, content_hash, parsed_requirements
				)
				VALUES (?, ?, ?, ?, ?, '[]'::jsonb)
				""",
			jobDescriptionId,
			userId,
			jobDescription,
			normalizedText,
			contentHash
		);

		chunker.chunk(normalizedText).forEach(chunk -> jdbcTemplate.update(
			"""
				INSERT INTO ai_interview_app.job_description_chunks (
					id, job_description_id, chunk_index, section, content, metadata
				)
				VALUES (?, ?, ?, ?, ?, jsonb_build_object('sourceType', 'job_description', 'contextId', ?))
				""",
			UUID.randomUUID(),
			jobDescriptionId,
			chunk.index(),
			chunk.section(),
			chunk.content(),
			RagContextId.forChunk("job_description", chunk)
		));

		return findDocument(userId, jobDescriptionId).orElseThrow();
	}

	private Optional<ResolvedDocument> queryDocument(String sql, Object... arguments) {
		return jdbcTemplate.query(
			sql,
			(rs, rowNum) -> {
				UUID id = rs.getObject("id", UUID.class);
				String normalizedText = rs.getString("normalized_text");
				String storedHash = rs.getString("content_hash");
				return new ResolvedDocument(
					DocumentSourceType.JOB_DESCRIPTION,
					id,
					storedHash == null ? contentHasher.sha256(normalizedText) : storedHash,
					normalizedText,
					findChunks(id)
				);
			},
			arguments
		).stream().findFirst();
	}

	private java.util.List<DocumentChunk> findChunks(UUID jobDescriptionId) {
		return jdbcTemplate.query(
			"""
				SELECT chunk_index, section, content
				FROM ai_interview_app.job_description_chunks
				WHERE job_description_id = ?
				ORDER BY chunk_index
				""",
			(rs, rowNum) -> new DocumentChunk(
				rs.getInt("chunk_index"),
				rs.getString("section"),
				rs.getString("content"),
				RagContextId.forChunk(
					"job_description",
					rs.getString("section"),
					rs.getInt("chunk_index")
				)
			),
			jobDescriptionId
		);
	}
}
