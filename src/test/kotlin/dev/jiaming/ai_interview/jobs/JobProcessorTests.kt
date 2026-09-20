package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class JobProcessorTests {
	private val objectMapper = ObjectMapper().findAndRegisterModules()

	@Test
	fun decodesPayloadAndDispatchesToRegisteredHandler() {
		val store = Mockito.mock(BackgroundJobStore::class.java)
		val materialization = Mockito.mock(JobEffectMaterializationService::class.java)
		val metrics = Mockito.mock(JobMetrics::class.java)
		val decoder = Mockito.mock(JobPayloadDecoder::class.java)
		val payload = AnalysisJobPayload(UUID.randomUUID(), null, "Backend", "Mid-level")
		val expected = objectMapper.createObjectNode().put("ok", true)
		val handler = TestHandler(expected)
		val registry = JobHandlerRegistry(listOf(NoOpHandler(JobType.RESUME_EXTRACTION, Any::class.java), handler, NoOpHandler(JobType.ANSWER_FEEDBACK, Any::class.java)))
		val processor = JobProcessor(decoder, registry, store, materialization, metrics, objectMapper)
		val job = job()
		val leaseToken = UUID.randomUUID()
		Mockito.`when`(decoder.decode(job, leaseToken, AnalysisJobPayload::class.java)).thenReturn(payload)

		val result = processor.process(job, leaseToken)

		assertThat(result).isSameAs(expected)
		assertThat(handler.payload).isSameAs(payload)
		assertThat(handler.context.job()).isSameAs(job)
		Mockito.verify(decoder).decode(job, leaseToken, AnalysisJobPayload::class.java)
	}

	private fun job(): BackgroundJob {
		val now = Instant.now()
		return BackgroundJob(UUID.randomUUID(), UUID.randomUUID(), JobType.ANALYSIS, "resume", UUID.randomUUID(), JobStatus.PROCESSING, JobStage.QUEUED, objectMapper.createObjectNode(), null, "fingerprint", 1, 3, null, null, null, now, now, now, now, now, null, UUID.randomUUID(), now.plusSeconds(300))
	}

	private class TestHandler(private val result: JsonNode) : JobHandler<AnalysisJobPayload> {
		lateinit var payload: AnalysisJobPayload
		lateinit var context: JobExecutionContext
		override fun type() = JobType.ANALYSIS
		override fun payloadType() = AnalysisJobPayload::class.java
		override fun handle(payload: AnalysisJobPayload, context: JobExecutionContext): JsonNode {
			this.payload = payload
			this.context = context
			return result
		}
	}

	private class NoOpHandler<P>(private val jobType: JobType, private val payloadClass: Class<P>) : JobHandler<P> {
		override fun type() = jobType
		override fun payloadType() = payloadClass
		override fun handle(payload: P, context: JobExecutionContext): JsonNode? = null
	}
}
