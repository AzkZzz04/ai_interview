package dev.jiaming.ai_interview.resume;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.jiaming.ai_interview.common.ContentHasher;
import dev.jiaming.ai_interview.common.LocalUserService;
import dev.jiaming.ai_interview.document.DocumentChunk;
import dev.jiaming.ai_interview.document.DocumentSourceType;
import dev.jiaming.ai_interview.document.ResolvedDocument;
import dev.jiaming.ai_interview.rag.RagContextId;

@Service
public class ResumePersistenceService {

	private final JdbcTemplate jdbcTemplate;

	private final LocalUserService localUserService;

	private final ResumeTextNormalizer normalizer;

	private final SectionAwareTextChunker chunker;

	private final ContentHasher contentHasher;

	public ResumePersistenceService(
		JdbcTemplate jdbcTemplate,
		LocalUserService localUserService,
		ResumeTextNormalizer normalizer,
		SectionAwareTextChunker chunker,
		ContentHasher contentHasher
	) {
		this.jdbcTemplate = jdbcTemplate;
		this.localUserService = localUserService;
		this.normalizer = normalizer;
		this.chunker = chunker;
		this.contentHasher = contentHasher;
	}

	@Transactional
	public ResumeUploadResponse save(
		String originalFilename,
		String contentType,
		String detectedContentType,
		long sizeBytes,
		String storageKey,
		String rawText,
		String normalizedText,
		List<ResumeChunkResponse> chunks
	) {
		UUID resumeId = createPending(
			originalFilename,
			contentType,
			detectedContentType,
			sizeBytes,
			storageKey
		);
		return completeExtraction(resumeId, rawText, normalizedText, chunks);
	}

	public UUID createPending(
		String originalFilename,
		String contentType,
		String detectedContentType,
		long sizeBytes,
		String storageKey
	) {
		return createPending(
			localUserService.localUserId(),
			originalFilename,
			contentType,
			detectedContentType,
			sizeBytes,
			storageKey
		);
	}

	public UUID createPending(
		UUID userId,
		String originalFilename,
		String contentType,
		String detectedContentType,
		long sizeBytes,
		String storageKey
	) {
		UUID resumeId = UUID.randomUUID();
		jdbcTemplate.update(
			"""
				INSERT INTO ai_interview_app.resumes (
					id, user_id, original_filename, content_type, detected_content_type,
					size_bytes, storage_key, raw_text, normalized_text, parsed_skills,
					processing_status, failure_code, failure_message, updated_at
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, '[]'::jsonb, 'PENDING', NULL, NULL, now())
				""",
			resumeId,
			userId,
			originalFilename,
			contentType,
			detectedContentType,
			sizeBytes,
			storageKey
		);
		return resumeId;
	}

	@Transactional
	public ResumeUploadResponse completeExtraction(
		UUID resumeId,
		String rawText,
		String normalizedText,
		List<ResumeChunkResponse> chunks
	) {
		String contentHash = contentHasher.sha256(normalizedText);
		int updated = jdbcTemplate.update(
			"""
				UPDATE ai_interview_app.resumes
				SET raw_text = ?,
					normalized_text = ?,
					processing_status = 'READY',
					failure_code = NULL,
					failure_message = NULL,
					content_hash = ?,
					updated_at = now()
				WHERE id = ? AND user_id = ? AND processing_status IN ('PENDING', 'READY')
				""",
			rawText,
			normalizedText,
			contentHash,
			resumeId,
			localUserService.localUserId()
		);
		if (updated != 1) {
			throw new IllegalStateException("Pending resume does not exist: " + resumeId);
		}

		jdbcTemplate.update("DELETE FROM ai_interview_app.resume_chunks WHERE resume_id = ?", resumeId);
		for (ResumeChunkResponse chunk : chunks) {
			jdbcTemplate.update(
				"""
					INSERT INTO ai_interview_app.resume_chunks (id, resume_id, chunk_index, section, content, metadata)
					VALUES (?, ?, ?, ?, ?, jsonb_build_object('sourceType', 'resume', 'contextId', ?))
					""",
				UUID.randomUUID(),
				resumeId,
				chunk.index(),
				chunk.section(),
				chunk.content(),
				RagContextId.forChunk("resume", chunk.section(), chunk.index())
			);
		}
		return findById(resumeId).orElseThrow();
	}

	public void deletePending(UUID resumeId) {
		jdbcTemplate.update(
			"DELETE FROM ai_interview_app.resumes WHERE id = ? AND processing_status = 'PENDING'",
			resumeId
		);
	}

	public Optional<String> markFailed(UUID resumeId, String errorCode, String errorMessage) {
		jdbcTemplate.update(
			"""
				UPDATE ai_interview_app.resumes
				SET processing_status = 'FAILED',
					failure_code = ?,
					failure_message = ?,
					updated_at = now()
				WHERE id = ? AND processing_status IN ('PENDING', 'READY', 'FAILED')
				""",
			errorCode,
			truncate(errorMessage),
			resumeId
		);
		return findStorageKey(resumeId);
	}

	public List<FailedResumeStorage> findFailedStorageObjects(int limit) {
		return jdbcTemplate.query(
			"""
				SELECT id, storage_key
				FROM ai_interview_app.resumes
				WHERE processing_status = 'FAILED'
				  AND storage_key IS NOT NULL
				  AND btrim(storage_key) <> ''
				ORDER BY updated_at
				LIMIT ?
				""",
			(rs, rowNum) -> new FailedResumeStorage(
				rs.getObject("id", UUID.class),
				rs.getString("storage_key")
			),
			limit
		);
	}

	public List<FailedResumeJob> findUnappliedTerminalFailures(int limit) {
		return jdbcTemplate.query(
			"""
				SELECT r.id, j.error_code, j.last_error
				FROM ai_interview_app.resumes r
				JOIN ai_interview_app.background_jobs j
				  ON j.resource_id = r.id AND j.job_type = 'RESUME_EXTRACTION'
				WHERE j.status = 'FAILED'
				  AND r.processing_status <> 'FAILED'
				ORDER BY j.completed_at
				LIMIT ?
				""",
			(rs, rowNum) -> new FailedResumeJob(
				rs.getObject("id", UUID.class),
				rs.getString("error_code"),
				rs.getString("last_error")
			),
			limit
		);
	}

	public boolean clearStorageKey(UUID resumeId, String expectedStorageKey) {
		return jdbcTemplate.update(
			"""
				UPDATE ai_interview_app.resumes
				SET storage_key = NULL, updated_at = now()
				WHERE id = ? AND processing_status = 'FAILED' AND storage_key = ?
				""",
			resumeId,
			expectedStorageKey
		) == 1;
	}

	public Optional<ResumeUploadResponse> findLatest() {
		UUID userId = localUserService.localUserId();
		List<ResumeUploadResponse> resumes = jdbcTemplate.query(
			"""
				SELECT id, original_filename, content_type, detected_content_type, size_bytes,
				       raw_text, normalized_text, created_at
				FROM ai_interview_app.resumes
				WHERE user_id = ? AND processing_status = 'READY'
				  AND normalized_text IS NOT NULL AND btrim(normalized_text) <> ''
				ORDER BY created_at DESC
				LIMIT 1
				""",
			(rs, rowNum) -> toUploadResponse(rs),
			userId
		);
		return resumes.stream().findFirst();
	}

	public Optional<String> findProcessingStatus(UUID userId, UUID resumeId) {
		return jdbcTemplate.query(
			"SELECT processing_status FROM ai_interview_app.resumes WHERE id = ? AND user_id = ?",
			(rs, rowNum) -> rs.getString("processing_status"),
			resumeId,
			userId
		).stream().findFirst();
	}

	public Optional<ResolvedDocument> findReadyDocument(UUID userId, UUID resumeId) {
		return queryDocument(
			"""
				SELECT id, normalized_text, content_hash
				FROM ai_interview_app.resumes
				WHERE id = ? AND user_id = ? AND processing_status = 'READY'
				  AND normalized_text IS NOT NULL AND btrim(normalized_text) <> ''
				""",
			resumeId,
			userId
		);
	}

	public Optional<ResolvedDocument> findLatestReadyDocument(UUID userId) {
		return queryDocument(
			"""
				SELECT id, normalized_text, content_hash
				FROM ai_interview_app.resumes
				WHERE user_id = ? AND processing_status = 'READY'
				  AND normalized_text IS NOT NULL AND btrim(normalized_text) <> ''
				ORDER BY created_at DESC
				LIMIT 1
				""",
			userId
		);
	}

	public Optional<ResolvedDocument> findReadyDocumentByContent(
		UUID userId,
		String contentHash,
		String normalizedText
	) {
		return queryDocument(
			"""
				SELECT id, normalized_text, content_hash
				FROM ai_interview_app.resumes
				WHERE user_id = ? AND processing_status = 'READY' AND content_hash = ?
				  AND normalized_text = ?
				ORDER BY created_at DESC
				LIMIT 1
				""",
			userId,
			contentHash,
			normalizedText
		);
	}

	@Transactional
	public ResolvedDocument findOrCreateDocument(UUID userId, String rawText) {
		String normalizedText = normalizer.normalize(rawText);
		String contentHash = contentHasher.sha256(normalizedText);
		Optional<ResolvedDocument> existing = findReadyDocumentByContent(userId, contentHash, normalizedText);
		if (existing.isPresent()) {
			return existing.get();
		}

		UUID resumeId = UUID.randomUUID();
		jdbcTemplate.update(
			"""
				INSERT INTO ai_interview_app.resumes (
					id, user_id, original_filename, content_type, detected_content_type,
					size_bytes, raw_text, normalized_text, content_hash, parsed_skills,
					processing_status, updated_at
				)
				VALUES (?, ?, 'pasted-resume.txt', 'text/plain', 'text/plain', ?, ?, ?, ?,
				        '[]'::jsonb, 'READY', now())
				""",
			resumeId,
			userId,
			normalizedText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
			rawText,
			normalizedText,
			contentHash
		);
		insertChunks(resumeId, normalizedText);
		return findReadyDocument(userId, resumeId).orElseThrow();
	}

	private Optional<ResumeUploadResponse> findById(UUID resumeId) {
		List<ResumeUploadResponse> resumes = jdbcTemplate.query(
			"""
				SELECT id, original_filename, content_type, detected_content_type, size_bytes,
				       raw_text, normalized_text, created_at
				FROM ai_interview_app.resumes
				WHERE id = ?
				""",
			(rs, rowNum) -> toUploadResponse(rs),
			resumeId
		);
		return resumes.stream().findFirst();
	}

	private ResumeUploadResponse toUploadResponse(ResultSet rs) throws SQLException {
		UUID resumeId = rs.getObject("id", UUID.class);
		String rawText = value(rs.getString("raw_text"));
		String normalizedText = value(rs.getString("normalized_text"));
		return new ResumeUploadResponse(
			resumeId.toString(),
			rs.getString("original_filename"),
			rs.getString("content_type"),
			rs.getString("detected_content_type"),
			rs.getLong("size_bytes"),
			rawText.length(),
			normalizedText.length(),
			normalizedText,
			findChunks(resumeId),
			rs.getTimestamp("created_at").toInstant()
		);
	}

	public List<ResumeChunkResponse> findChunks(UUID resumeId) {
		return jdbcTemplate.query(
			"""
				SELECT chunk_index, section, content
				FROM ai_interview_app.resume_chunks
				WHERE resume_id = ?
				ORDER BY chunk_index
				""",
			(rs, rowNum) -> new ResumeChunkResponse(
				rs.getInt("chunk_index"),
				rs.getString("section"),
				rs.getString("content"),
				rs.getString("content").length()
			),
			resumeId
		);
	}

	private Optional<ResolvedDocument> queryDocument(String sql, Object... arguments) {
		List<ResolvedDocument> documents = jdbcTemplate.query(
			sql,
			(rs, rowNum) -> {
				UUID id = rs.getObject("id", UUID.class);
				String normalizedText = rs.getString("normalized_text");
				String storedHash = rs.getString("content_hash");
				String contentHash = storedHash == null
					? contentHasher.sha256(normalizedText)
					: storedHash;
				return new ResolvedDocument(
					DocumentSourceType.RESUME,
					id,
					contentHash,
					normalizedText,
					findDocumentChunks(id)
				);
			},
			arguments
		);
		return documents.stream().findFirst();
	}

	private List<DocumentChunk> findDocumentChunks(UUID resumeId) {
		return findChunks(resumeId).stream()
			.map(chunk -> new DocumentChunk(
				chunk.index(),
				chunk.section(),
				chunk.content(),
				RagContextId.forChunk("resume", chunk.section(), chunk.index())
			))
			.toList();
	}

	private void insertChunks(UUID resumeId, String normalizedText) {
		for (TextChunk chunk : chunker.chunk(normalizedText)) {
			jdbcTemplate.update(
				"""
					INSERT INTO ai_interview_app.resume_chunks (id, resume_id, chunk_index, section, content, metadata)
					VALUES (?, ?, ?, ?, ?, jsonb_build_object('sourceType', 'resume', 'contextId', ?))
					""",
				UUID.randomUUID(),
				resumeId,
				chunk.index(),
				chunk.section(),
				chunk.content(),
				RagContextId.forChunk("resume", chunk)
			);
		}
	}

	private Optional<String> findStorageKey(UUID resumeId) {
		List<String> storageKeys = jdbcTemplate.query(
			"SELECT storage_key FROM ai_interview_app.resumes WHERE id = ?",
			(rs, rowNum) -> rs.getString("storage_key"),
			resumeId
		);
		return storageKeys.stream().filter(value -> value != null && !value.isBlank()).findFirst();
	}

	private String truncate(String value) {
		if (value == null || value.length() <= 4_000) {
			return value;
		}
		return value.substring(0, 4_000);
	}

	private String value(String value) {
		return value == null ? "" : value;
	}
}
