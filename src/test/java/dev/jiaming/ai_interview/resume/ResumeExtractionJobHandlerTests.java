package dev.jiaming.ai_interview.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.jiaming.ai_interview.jobs.JobStage;

class ResumeExtractionJobHandlerTests {

	@Test
	void reportsRealStagesAndCompletesPendingResume() {
		ResumeStorageService storage = mock(ResumeStorageService.class);
		ResumeTextExtractor extractor = mock(ResumeTextExtractor.class);
		ResumePersistenceService persistence = mock(ResumePersistenceService.class);
		ResumeTextNormalizer normalizer = new ResumeTextNormalizer();
		SectionAwareTextChunker chunker = new SectionAwareTextChunker();
		ResumeExtractionJobHandler handler = new ResumeExtractionJobHandler(
			storage, extractor, normalizer, chunker, persistence
		);
		UUID resumeId = UUID.randomUUID();
		ResumeExtractionJobPayload payload = new ResumeExtractionJobPayload(
			resumeId, "resumes/key", "resume.txt", "text/plain", "text/plain", 22, "txt"
		);
		ResumeFileContent content = new ResumeFileContent(
			"resume.txt", "text/plain", 22, "SKILLS\nJava".getBytes(StandardCharsets.UTF_8), "text/plain", "txt"
		);
		ResumeUploadResponse expected = mock(ResumeUploadResponse.class);
		when(storage.read(payload)).thenReturn(content);
		when(extractor.extract(content)).thenReturn("SKILLS\nJava");
		when(persistence.completeExtraction(org.mockito.ArgumentMatchers.eq(resumeId),
			org.mockito.ArgumentMatchers.eq("SKILLS\nJava"), org.mockito.ArgumentMatchers.eq("SKILLS\nJava"),
			org.mockito.ArgumentMatchers.anyList())).thenReturn(expected);
		List<JobStage> stages = new ArrayList<>();

		ResumeUploadResponse result = handler.extract(payload, stages::add);

		assertThat(result).isSameAs(expected);
		assertThat(stages).containsExactly(
			JobStage.READING_FILE,
			JobStage.EXTRACTING_TEXT,
			JobStage.NORMALIZING_TEXT,
			JobStage.CHUNKING_TEXT
		);
		verify(storage).markReady("resumes/key");
	}
}
