package dev.jiaming.ai_interview.interview;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import dev.jiaming.ai_interview.rag.RagContextId;
import dev.jiaming.ai_interview.resume.ResumeTextNormalizer;
import dev.jiaming.ai_interview.resume.SectionAwareTextChunker;

@Service
public class JobDescriptionPersistenceService {

	private final JdbcTemplate jdbcTemplate;

	private final ResumeTextNormalizer normalizer;

	private final SectionAwareTextChunker chunker;

	public JobDescriptionPersistenceService(
		JdbcTemplate jdbcTemplate,
		ResumeTextNormalizer normalizer,
		SectionAwareTextChunker chunker
	) {
		this.jdbcTemplate = jdbcTemplate;
		this.normalizer = normalizer;
		this.chunker = chunker;
	}

	public Optional<UUID> save(UUID userId, String jobDescription) {
		if (jobDescription == null || jobDescription.isBlank()) {
			return Optional.empty();
		}
		String normalizedText = normalizer.normalize(jobDescription);
		if (normalizedText.isBlank()) {
			return Optional.empty();
		}

		UUID jobDescriptionId = UUID.randomUUID();
		jdbcTemplate.update(
			"""
				INSERT INTO ai_interview_app.job_descriptions (
					id, user_id, raw_text, normalized_text, parsed_requirements
				)
				VALUES (?, ?, ?, ?, '[]'::jsonb)
				""",
			jobDescriptionId,
			userId,
			jobDescription,
			normalizedText
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

		return Optional.of(jobDescriptionId);
	}
}
