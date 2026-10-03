package dev.jiaming.ai_interview.resume

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.jobs.JobExecutionContext
import dev.jiaming.ai_interview.jobs.JobStage
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.function.Supplier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.eq
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
		val expected = ResumeExtractionResult(resumeId.toString(), null)
		val context = Mockito.mock(JobExecutionContext::class.java)
		val expectedJson = ObjectMapper().createObjectNode().put("resumeId", resumeId.toString())
		Mockito.`when`(storage.read(payload)).thenReturn(content)
		Mockito.`when`(extractor.extract(content)).thenReturn("SKILLS\nJava")
		Mockito.`when`(persistence.completeExtractionForJob(eq(resumeId), eq("resumes/key"), eq("SKILLS\nJava"), eq("SKILLS\nJava"), org.mockito.ArgumentMatchers.anyList())).thenReturn(expected)
		Mockito.`when`(context.withOwnedLease<ResumeExtractionResult>(org.mockito.kotlin.any<Supplier<ResumeExtractionResult>>())).thenAnswer { invocation -> invocation.getArgument<Supplier<ResumeExtractionResult>>(0).get() }
		Mockito.`when`(context.toJson(expected)).thenReturn(expectedJson)

		val result = handler.handle(payload, context)

		assertThat(result).isSameAs(expectedJson)
		val stages = Mockito.inOrder(context)
		stages.verify(context).stage(JobStage.READING_FILE)
		stages.verify(context).stage(JobStage.EXTRACTING_TEXT)
		stages.verify(context).stage(JobStage.NORMALIZING_TEXT)
		stages.verify(context).stage(JobStage.CHUNKING_TEXT)
		Mockito.verify(storage).markReady("resumes/key")
		Mockito.verify(context).saveRootCheckpoint(expected, "resume-extraction")
	}

	@Test
	fun checkpointsDuplicateResultAndLeavesCleanupToTheReconciler() {
		val storage = Mockito.mock(ResumeStorageService::class.java)
		val extractor = Mockito.mock(ResumeTextExtractor::class.java)
		val persistence = Mockito.mock(ResumePersistenceService::class.java)
		val handler = ResumeExtractionJobHandler(storage, extractor, ResumeTextNormalizer(), SectionAwareTextChunker(), persistence)
		val resumeId = UUID.randomUUID()
		val payload = ResumeExtractionJobPayload(resumeId, "resumes/temporary", "copy.txt", "text/plain", "text/plain", 22, "txt")
		val content = ResumeFileContent("copy.txt", "text/plain", 22, "SKILLS\nJava".toByteArray(StandardCharsets.UTF_8), "text/plain", "txt")
		val expected = ResumeExtractionResult(resumeId.toString(), DuplicateResume(UUID.randomUUID().toString(), "Saved"))
		val context = Mockito.mock(JobExecutionContext::class.java)
		val expectedJson = ObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(expected)
		Mockito.`when`(storage.read(payload)).thenReturn(content)
		Mockito.`when`(extractor.extract(content)).thenReturn("SKILLS\nJava")
		Mockito.`when`(persistence.completeExtractionForJob(eq(resumeId), eq(payload.storageKey), eq("SKILLS\nJava"), eq("SKILLS\nJava"), org.mockito.ArgumentMatchers.anyList())).thenReturn(expected)
		Mockito.`when`(context.withOwnedLease<ResumeExtractionResult>(org.mockito.kotlin.any<Supplier<ResumeExtractionResult>>())).thenAnswer { invocation -> invocation.getArgument<Supplier<ResumeExtractionResult>>(0).get() }
		Mockito.`when`(context.toJson(expected)).thenReturn(expectedJson)

		assertThat(handler.handle(payload, context)).isSameAs(expectedJson)
		Mockito.verify(context).saveRootCheckpoint(expected, "resume-extraction")
		Mockito.verify(storage, Mockito.never()).markReady(payload.storageKey)
	}

	@Test
	fun retryReturnsCheckpointBeforeReadingOrExtractingTheTemporaryObject() {
		val storage = Mockito.mock(ResumeStorageService::class.java)
		val extractor = Mockito.mock(ResumeTextExtractor::class.java)
		val persistence = Mockito.mock(ResumePersistenceService::class.java)
		val handler = ResumeExtractionJobHandler(storage, extractor, ResumeTextNormalizer(), SectionAwareTextChunker(), persistence)
		val payload = ResumeExtractionJobPayload(UUID.randomUUID(), "resumes/temporary", "copy.txt", "text/plain", "text/plain", 22, "txt")
		val expected = ResumeExtractionResult(payload.resumeId.toString(), DuplicateResume(UUID.randomUUID().toString(), "Saved"))
		val expectedJson = ObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(expected)
		val context = Mockito.mock(JobExecutionContext::class.java)
		Mockito.`when`(context.rootCheckpoint(ResumeExtractionResult::class.java, "resumeId")).thenReturn(expected)
		Mockito.`when`(context.toJson(expected)).thenReturn(expectedJson)

		assertThat(handler.handle(payload, context)).isSameAs(expectedJson)

		Mockito.verify(storage, Mockito.never()).read(payload)
		Mockito.verify(storage, Mockito.never()).markReady(payload.storageKey)
		Mockito.verify(extractor, Mockito.never()).extract(org.mockito.kotlin.any())
		Mockito.verifyNoInteractions(persistence)
		Mockito.verify(context, Mockito.never()).stage(org.mockito.kotlin.any())
	}

	@Test
	fun retryWithMaterializedResumeRestoresReadyStorageTagBeforeReturningCheckpoint() {
		val storage = Mockito.mock(ResumeStorageService::class.java)
		val handler = ResumeExtractionJobHandler(
			storage, Mockito.mock(ResumeTextExtractor::class.java), ResumeTextNormalizer(),
			SectionAwareTextChunker(), Mockito.mock(ResumePersistenceService::class.java)
		)
		val payload = ResumeExtractionJobPayload(UUID.randomUUID(), "resumes/key", "resume.txt", "text/plain", "text/plain", 22, "txt")
		val expected = ResumeExtractionResult(payload.resumeId.toString(), null)
		val expectedJson = ObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(expected)
		val context = Mockito.mock(JobExecutionContext::class.java)
		Mockito.`when`(context.rootCheckpoint(ResumeExtractionResult::class.java, "resumeId")).thenReturn(expected)
		Mockito.`when`(context.toJson(expected)).thenReturn(expectedJson)

		assertThat(handler.handle(payload, context)).isSameAs(expectedJson)

		Mockito.verify(storage).markReady(payload.storageKey)
		Mockito.verify(storage, Mockito.never()).read(payload)
	}
}
