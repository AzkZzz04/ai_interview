package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito

class JobExecutionContextTests {
	@Test
	fun laterCheckpointRetainsEarlierAssessmentEnvelope() {
		val objectMapper = ObjectMapper()
		val store = Mockito.mock(BackgroundJobStore::class.java)
		val leaseToken = UUID.randomUUID()
		val job = job(objectMapper, null)
		val context = JobExecutionContext(job, leaseToken, store, Mockito.mock(JobEffectMaterializationService::class.java), Mockito.mock(JobMetrics::class.java), objectMapper)

		context.saveCheckpoint("assessment", objectMapper.createObjectNode().put("overallScore", 80))
		context.saveCheckpoint("questions", objectMapper.createArrayNode().add("question"))

		val checkpoints = ArgumentCaptor.forClass(JsonNode::class.java)
		Mockito.verify(store, Mockito.times(2)).checkpointResult(eq(job.id()), eq(leaseToken), checkpoints.capture())
		val latest = checkpoints.allValues.last()
		assertThat(latest.hasNonNull("assessment")).isTrue()
		assertThat(latest.hasNonNull("questions")).isTrue()
	}

	@Test
	fun stageChangesUpdateLeaseOwnedRowAndRecordPreviousStageDuration() {
		val objectMapper = ObjectMapper()
		val store = Mockito.mock(BackgroundJobStore::class.java)
		val metrics = Mockito.mock(JobMetrics::class.java)
		val leaseToken = UUID.randomUUID()
		val job = job(objectMapper, objectMapper.createObjectNode())
		val context = JobExecutionContext(job, leaseToken, store, Mockito.mock(JobEffectMaterializationService::class.java), metrics, objectMapper)

		context.stage(JobStage.ASSESSING_RESUME)
		context.stage(JobStage.GENERATING_QUESTIONS)
		context.finish()

		Mockito.verify(store).updateStage(job.id(), leaseToken, JobStage.ASSESSING_RESUME)
		Mockito.verify(store).updateStage(job.id(), leaseToken, JobStage.GENERATING_QUESTIONS)
		Mockito.verify(metrics).stageDuration(eq(JobType.ANALYSIS), eq(JobStage.ASSESSING_RESUME), any())
		Mockito.verify(metrics).stageDuration(eq(JobType.ANALYSIS), eq(JobStage.GENERATING_QUESTIONS), any())
	}

	private fun job(objectMapper: ObjectMapper, result: JsonNode?): BackgroundJob {
		val now = Instant.now()
		return BackgroundJob(UUID.randomUUID(), UUID.randomUUID(), JobType.ANALYSIS, "resume", UUID.randomUUID(), JobStatus.PROCESSING, JobStage.QUEUED, objectMapper.createObjectNode(), result, "fingerprint", 1, 3, null, null, null, now, now, now, now, now, null, UUID.randomUUID(), now.plusSeconds(300))
	}
}
