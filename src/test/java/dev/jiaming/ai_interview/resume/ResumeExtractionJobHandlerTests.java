package dev.jiaming.ai_interview.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import dev.jiaming.ai_interview.jobs.JobExecutionContext;
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
		JobExecutionContext context = mock(JobExecutionContext.class);
		var expectedJson = new ObjectMapper().createObjectNode().put("resumeId", resumeId.toString());
		when(storage.read(payload)).thenReturn(content);
		when(extractor.extract(content)).thenReturn("SKILLS\nJava");
		when(persistence.completeExtraction(org.mockito.ArgumentMatchers.eq(resumeId),
			org.mockito.ArgumentMatchers.eq("SKILLS\nJava"), org.mockito.ArgumentMatchers.eq("SKILLS\nJava"),
			org.mockito.ArgumentMatchers.anyList())).thenReturn(expected);
		when(context.withOwnedLease(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation ->
			invocation.<Supplier<ResumeUploadResponse>>getArgument(0).get()
		);
		when(context.toJson(expected)).thenReturn(expectedJson);

		var result = handler.handle(payload, context);

		assertThat(result).isSameAs(expectedJson);
		InOrder stages = inOrder(context);
		stages.verify(context).stage(JobStage.READING_FILE);
		stages.verify(context).stage(JobStage.EXTRACTING_TEXT);
		stages.verify(context).stage(JobStage.NORMALIZING_TEXT);
		stages.verify(context).stage(JobStage.CHUNKING_TEXT);
		verify(storage).markReady("resumes/key");
	}
}
