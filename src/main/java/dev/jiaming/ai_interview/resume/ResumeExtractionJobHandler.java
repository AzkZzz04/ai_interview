package dev.jiaming.ai_interview.resume;

import java.util.List;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import dev.jiaming.ai_interview.jobs.JobStage;

@Service
public class ResumeExtractionJobHandler {

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

	public ResumeUploadResponse extract(
		ResumeExtractionJobPayload payload,
		Consumer<JobStage> stageReporter
	) {
		stageReporter.accept(JobStage.READING_FILE);
		ResumeFileContent content = storageService.read(payload);

		stageReporter.accept(JobStage.EXTRACTING_TEXT);
		String rawText = textExtractor.extract(content);

		stageReporter.accept(JobStage.NORMALIZING_TEXT);
		String normalizedText = normalizer.normalize(rawText);
		if (normalizedText.isBlank()) {
			throw new ResumeExtractionException("No readable resume text was extracted");
		}

		stageReporter.accept(JobStage.CHUNKING_TEXT);
		List<ResumeChunkResponse> chunks = chunker.chunk(normalizedText).stream()
			.map(chunk -> new ResumeChunkResponse(
				chunk.index(),
				chunk.section(),
				chunk.content(),
				chunk.content().length()
			))
			.toList();
		ResumeUploadResponse response = persistenceService.completeExtraction(
			payload.resumeId(), rawText, normalizedText, chunks
		);
		storageService.markReady(payload.storageKey());
		return response;
	}
}
