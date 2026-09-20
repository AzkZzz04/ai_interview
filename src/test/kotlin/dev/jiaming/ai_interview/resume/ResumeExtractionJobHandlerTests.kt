package dev.jiaming.ai_interview.resume

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.jobs.JobExecutionContext
import dev.jiaming.ai_interview.jobs.JobStage
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.function.Supplier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class ResumeExtractionJobHandlerTests {
	@Test
	fun reportsRealStagesAndCompletesPendingResume() {
		val storage = Mockito.mock(ResumeStorageService::class.java)
		val extractor = Mockito.mock(ResumeTextExtractor::class.java)
		val persistence = Mockito.mock(ResumePersistenceService::class.java)
		val handler = ResumeExtractionJobHandler(storage, extractor, ResumeTextNormalizer(), SectionAwareTextChunker(), persistence)
		val resumeId = UUID.randomUUID()
		val payload = ResumeExtractionJobPayload(resumeId, "resumes/key", "resume.txt", "text/plain", "text/plain", 22, "txt")
		val content = ResumeFileContent("resume.txt", "text/plain", 22, "SKILLS\nJava".toByteArray(StandardCharsets.UTF_8), "text/plain", "txt")
		val expected = Mockito.mock(ResumeUploadResponse::class.java)
		val context = Mockito.mock(JobExecutionContext::class.java)
		val expectedJson = ObjectMapper().createObjectNode().put("resumeId", resumeId.toString())
		Mockito.`when`(storage.read(payload)).thenReturn(content)
		Mockito.`when`(extractor.extract(content)).thenReturn("SKILLS\nJava")
		Mockito.`when`(persistence.completeExtraction(org.mockito.ArgumentMatchers.eq(resumeId), org.mockito.ArgumentMatchers.eq("SKILLS\nJava"), org.mockito.ArgumentMatchers.eq("SKILLS\nJava"), org.mockito.ArgumentMatchers.anyList())).thenReturn(expected)
		Mockito.`when`(context.withOwnedLease<ResumeUploadResponse>(org.mockito.ArgumentMatchers.any())).thenAnswer { invocation -> invocation.getArgument<Supplier<ResumeUploadResponse>>(0).get() }
		Mockito.`when`(context.toJson(expected)).thenReturn(expectedJson)

		val result = handler.handle(payload, context)

		assertThat(result).isSameAs(expectedJson)
		val stages = Mockito.inOrder(context)
		stages.verify(context).stage(JobStage.READING_FILE)
		stages.verify(context).stage(JobStage.EXTRACTING_TEXT)
		stages.verify(context).stage(JobStage.NORMALIZING_TEXT)
		stages.verify(context).stage(JobStage.CHUNKING_TEXT)
		Mockito.verify(storage).markReady("resumes/key")
	}
}
