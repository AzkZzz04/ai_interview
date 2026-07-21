package dev.jiaming.ai_interview.resume;

import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import dev.jiaming.ai_interview.jobs.JobExecutionContext;
import dev.jiaming.ai_interview.jobs.JobHandler;
import dev.jiaming.ai_interview.jobs.JobStage;
import dev.jiaming.ai_interview.jobs.JobType;

@Service
public class ResumeExtractionJobHandler implements JobHandler<ResumeExtractionJobPayload> {

	private final ResumeStorageService storageService;

	private final ResumeTextExtractor textExtractor;

	private final ResumeTextNormalizer normalizer;

	private final SectionAwareTextChunker chunker;

	private final ResumePersistenceService persistenceService;

	public ResumeExtractionJobHandler(
		ResumeStorageService storageService,
		ResumeTextExtractor textExtractor,
		ResumeTextNormalizer normalizer,
		SectionAwareTextChunker chunker,
		ResumePersistenceService persistenceService
	) {
		this.storageService = storageService;
		this.textExtractor = textExtractor;
		this.normalizer = normalizer;
		this.chunker = chunker;
		this.persistenceService = persistenceService;
	}

	@Override
	public JobType type() {
		return JobType.RESUME_EXTRACTION;
	}

	@Override
	public Class<ResumeExtractionJobPayload> payloadType() {
		return ResumeExtractionJobPayload.class;
	}

	@Override
	public JsonNode handle(ResumeExtractionJobPayload payload, JobExecutionContext context) {
		context.stage(JobStage.READING_FILE);
		ResumeFileContent content = storageService.read(payload);

		context.stage(JobStage.EXTRACTING_TEXT);
		String rawText = textExtractor.extract(content);

		context.stage(JobStage.NORMALIZING_TEXT);
		String normalizedText = normalizer.normalize(rawText);
		if (normalizedText.isBlank()) {
			throw new ResumeExtractionException("No readable resume text was extracted");
		}

		context.stage(JobStage.CHUNKING_TEXT);
		List<ResumeChunkResponse> chunks = chunker.chunk(normalizedText).stream()
			.map(chunk -> new ResumeChunkResponse(
				chunk.index(),
				chunk.section(),
				chunk.content(),
				chunk.content().length()
			))
			.toList();
		ResumeUploadResponse response = context.withOwnedLease(() -> persistenceService.completeExtraction(
			payload.resumeId(), rawText, normalizedText, chunks
		));
		storageService.markReady(payload.storageKey());
		return context.toJson(response);
	}
}
