package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.coach.AiAnalysisRequest
import dev.jiaming.ai_interview.coach.AnswerFeedbackRequest
import dev.jiaming.ai_interview.document.DocumentReferenceResolver
import dev.jiaming.ai_interview.document.DocumentSourceType
import dev.jiaming.ai_interview.document.ResolvedDocument
import dev.jiaming.ai_interview.document.ResolvedJobInputs
import java.time.Instant
import java.util.Optional
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.Mockito

class JobPayloadDecoderTests {
	private val objectMapper = ObjectMapper().findAndRegisterModules()
	private val jobStore = Mockito.mock(BackgroundJobStore::class.java)
	private val resolver = Mockito.mock(DocumentReferenceResolver::class.java)
	private val decoder = JobPayloadDecoder(objectMapper, jobStore, resolver)

	@Test
	fun readsCurrentPayloadWithoutMaterializingDocuments() {
		val payload = AnalysisJobPayload(UUID.randomUUID(), UUID.randomUUID(), "Backend", "Mid-level")
		val job = job(objectMapper.valueToTree(payload))
		val decoded = decoder.decode(job, UUID.randomUUID(), AnalysisJobPayload::class.java)
		assertThat(decoded).isEqualTo(payload)
		Mockito.verifyNoInteractions(resolver)
		Mockito.verify(jobStore, Mockito.never()).replaceRequestPayload(any(), any(), any())
	}

	@Test
	fun readsCurrentFeedbackPayloadWithThePersistedJsonShape() {
		val payload = FeedbackJobPayload(
			FeedbackJobPayload.CURRENT_VERSION,
			UUID.randomUUID(), null, "Backend", "Mid-level", "Explain the design", "Architecture",
			listOf("trade-offs", "failure handling"), "I chose Postgres for durable jobs"
		)
		val stored = objectMapper.valueToTree<JsonNode>(payload)
		assertThat(stored.fieldNames().asSequence().toList()).containsExactly(
			"payloadVersion", "resumeId", "jobDescriptionId", "targetRole", "seniority", "questionText",
			"category", "expectedSignals", "answerText"
		)

		val decoded = decoder.decode(job(stored, JobType.ANSWER_FEEDBACK), UUID.randomUUID(), FeedbackJobPayload::class.java) as FeedbackJobPayload

		assertThat(decoded).isEqualTo(payload)
		assertThat(decoded.expectedSignals()).containsExactly("trade-offs", "failure handling")
		Mockito.verifyNoInteractions(resolver)
	}

	@Test
	fun upgradesLegacyTextPayloadToStableResourceReferences() {
		val resumeMarker = "LEGACY_RESUME_PII_04C"
		val jobMarker = "LEGACY_JOB_PII_88A"
		val job = job(objectMapper.valueToTree(AiAnalysisRequest(resumeMarker, jobMarker, "Backend", "Mid-level")))
		val leaseToken = UUID.randomUUID()
		val resumeId = UUID.randomUUID()
		val jobDescriptionId = UUID.randomUUID()
		val resolved = ResolvedJobInputs(
			ResolvedDocument(DocumentSourceType.RESUME, resumeId, "resume-hash", resumeMarker, emptyList()),
			Optional.of(ResolvedDocument(DocumentSourceType.JOB_DESCRIPTION, jobDescriptionId, "jd-hash", jobMarker, emptyList()))
		)
		Mockito.`when`(resolver.resolveLegacy(requireNotNull(job.userId), null, resumeMarker, null, jobMarker)).thenReturn(resolved)

		val decoded = decoder.analysis(job, leaseToken)

		assertThat(decoded.resumeId).isEqualTo(resumeId)
		assertThat(decoded.jobDescriptionId).isEqualTo(jobDescriptionId)
		val upgradedJson = objectMapper.valueToTree<JsonNode>(decoded)
		assertThat(upgradedJson.toString()).doesNotContain(resumeMarker, jobMarker)
		Mockito.verify(jobStore).replaceRequestPayload(job.id, leaseToken, upgradedJson)
	}

	@Test
	fun upgradesLegacyFeedbackPayloadAndPersistsExpectedSignals() {
		val resumeMarker = "LEGACY_FEEDBACK_RESUME_PII_04C"
		val jobMarker = "LEGACY_FEEDBACK_JOB_PII_88A"
		val legacy = AnswerFeedbackRequest(resumeMarker, jobMarker, "Backend", "Mid-level", "Explain the design", "Architecture", listOf("trade-offs", "failure handling"), "I chose Postgres")
		val job = job(objectMapper.valueToTree(legacy), JobType.ANSWER_FEEDBACK)
		val leaseToken = UUID.randomUUID()
		val resumeId = UUID.randomUUID()
		val jobDescriptionId = UUID.randomUUID()
		val resolved = ResolvedJobInputs(
			ResolvedDocument(DocumentSourceType.RESUME, resumeId, "resume-hash", resumeMarker, emptyList()),
			Optional.of(ResolvedDocument(DocumentSourceType.JOB_DESCRIPTION, jobDescriptionId, "jd-hash", jobMarker, emptyList()))
		)
		Mockito.`when`(resolver.resolveLegacy(requireNotNull(job.userId), null, resumeMarker, null, jobMarker)).thenReturn(resolved)

		val decoded = decoder.decode(job, leaseToken, FeedbackJobPayload::class.java) as FeedbackJobPayload

		assertThat(decoded.resumeId).isEqualTo(resumeId)
		assertThat(decoded.jobDescriptionId).isEqualTo(jobDescriptionId)
		assertThat(decoded.expectedSignals()).containsExactly("trade-offs", "failure handling")
		val upgradedJson = objectMapper.valueToTree<JsonNode>(decoded)
		assertThat(upgradedJson.path("payloadVersion").asInt()).isEqualTo(FeedbackJobPayload.CURRENT_VERSION)
		assertThat(upgradedJson.path("expectedSignals").map { it.asText() }).containsExactly("trade-offs", "failure handling")
		assertThat(upgradedJson.toString()).doesNotContain(resumeMarker, jobMarker)
		Mockito.verify(jobStore).replaceRequestPayload(job.id, leaseToken, upgradedJson)
	}

	private fun job(payload: JsonNode, jobType: JobType = JobType.ANALYSIS): BackgroundJob {
		val now = Instant.now()
		return BackgroundJob(UUID.randomUUID(), UUID.randomUUID(), jobType, "resume", null, JobStatus.PROCESSING, JobStage.QUEUED, payload, null, "fingerprint", 1, 3, null, null, null, now, now, now, now, now, null, UUID.randomUUID(), now.plusSeconds(300))
	}
}
