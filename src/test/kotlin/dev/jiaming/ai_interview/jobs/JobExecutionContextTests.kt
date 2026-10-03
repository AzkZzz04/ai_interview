package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.Mockito

class JobExecutionContextTests {
	@Test
	fun laterCheckpointRetainsEarlierCheckpoint() {
		val objectMapper = ObjectMapper()
		val store = Mockito.mock(BackgroundJobStore::class.java)
		val leaseToken = UUID.randomUUID()
		val job = job(objectMapper, null)
		val context = JobExecutionContext(job, leaseToken, store, Mockito.mock(JobEffectMaterializationService::class.java), Mockito.mock(JobMetrics::class.java), objectMapper)

		context.saveCheckpoint("split", objectMapper.createObjectNode().put("items", 1))
		context.saveCheckpoint("result", objectMapper.createArrayNode().add("item"))

		val checkpoints = ArgumentCaptor.forClass(JsonNode::class.java)
		Mockito.verify(store, Mockito.times(2)).checkpointResult(eq(job.id), eq(leaseToken), checkpoints.capture())
		val latest = checkpoints.allValues.last()
		assertThat(latest.hasNonNull("split")).isTrue()
		assertThat(latest.hasNonNull("result")).isTrue()
	}

	@Test
	fun stageChangesUpdateLeaseOwnedRowAndRecordPreviousStageDuration() {
		val objectMapper = ObjectMapper()
		val store = Mockito.mock(BackgroundJobStore::class.java)
		val metrics = Mockito.mock(JobMetrics::class.java)
		val leaseToken = UUID.randomUUID()
		val job = job(objectMapper, objectMapper.createObjectNode())
		val context = JobExecutionContext(job, leaseToken, store, Mockito.mock(JobEffectMaterializationService::class.java), metrics, objectMapper)

		context.stage(JobStage.RETRIEVING_EXPERIENCE)
		context.stage(JobStage.MATCHING_EXPERIENCE)
		context.finish()

		Mockito.verify(store).updateStage(job.id, leaseToken, JobStage.RETRIEVING_EXPERIENCE)
		Mockito.verify(store).updateStage(job.id, leaseToken, JobStage.MATCHING_EXPERIENCE)
		Mockito.verify(metrics).stageDuration(eq(JobType.EXPERIENCE_SUGGESTIONS), eq(JobStage.RETRIEVING_EXPERIENCE), any())
		Mockito.verify(metrics).stageDuration(eq(JobType.EXPERIENCE_SUGGESTIONS), eq(JobStage.MATCHING_EXPERIENCE), any())
	}

	private fun job(objectMapper: ObjectMapper, result: JsonNode?): BackgroundJob {
		val now = Instant.now()
		return BackgroundJob(UUID.randomUUID(), UUID.randomUUID(), JobType.EXPERIENCE_SUGGESTIONS, "resume", UUID.randomUUID(), JobStatus.PROCESSING, JobStage.QUEUED, objectMapper.createObjectNode(), result, "fingerprint", 1, 3, null, null, null, now, now, now, now, now, null, UUID.randomUUID(), now.plusSeconds(300))
	}
}
