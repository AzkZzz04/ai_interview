package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.coach.AiAnalysisRequest
import dev.jiaming.ai_interview.document.DocumentReferenceResolver
import dev.jiaming.ai_interview.document.DocumentSourceType
import dev.jiaming.ai_interview.document.ResolvedDocument
import dev.jiaming.ai_interview.document.ResolvedJobInputs
import java.time.Instant
import java.util.Optional
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
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
		Mockito.`when`(resolver.resolveLegacy(job.userId(), null, resumeMarker, null, jobMarker)).thenReturn(resolved)

		val decoded = decoder.analysis(job, leaseToken)

		assertThat(decoded.resumeId()).isEqualTo(resumeId)
		assertThat(decoded.jobDescriptionId()).isEqualTo(jobDescriptionId)
		val upgradedJson = objectMapper.valueToTree<JsonNode>(decoded)
		assertThat(upgradedJson.toString()).doesNotContain(resumeMarker, jobMarker)
		Mockito.verify(jobStore).replaceRequestPayload(job.id(), leaseToken, upgradedJson)
	}

	private fun job(payload: JsonNode): BackgroundJob {
		val now = Instant.now()
		return BackgroundJob(UUID.randomUUID(), UUID.randomUUID(), JobType.ANALYSIS, "resume", null, JobStatus.PROCESSING, JobStage.QUEUED, payload, null, "fingerprint", 1, 3, null, null, null, now, now, now, now, now, null, UUID.randomUUID(), now.plusSeconds(300))
	}
}
