package dev.jiaming.ai_interview.jobs

import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class JobDispatcherTests {
	private val jobStore = Mockito.mock(BackgroundJobStore::class.java)
	private val queueService = Mockito.mock(JobQueueService::class.java)
	private val metrics = Mockito.mock(JobMetrics::class.java)

	@Test
	fun workerRuntimeCanDispatchDueRetryMessages() {
		val dispatcher = JobDispatcher(properties(), jobStore, queueService, metrics)
		val jobId = UUID.randomUUID()
		assertThat(dispatcher.dispatch(jobId)).isTrue()
		Mockito.verify(queueService).send(jobId)
		Mockito.verify(jobStore).markEnqueued(jobId)
	}

	@Test
	fun recoveryPublishesEveryDueUndispatchedJob() {
		val dispatcher = JobDispatcher(properties(), jobStore, queueService, metrics)
		val first = UUID.randomUUID()
		val second = UUID.randomUUID()
		Mockito.`when`(jobStore.findUndispatched(25)).thenReturn(listOf(first, second))
		dispatcher.recoverUndispatchedJobs()
		Mockito.verify(queueService).send(first)
		Mockito.verify(queueService).send(second)
		Mockito.verify(jobStore).markEnqueued(first)
		Mockito.verify(jobStore).markEnqueued(second)
	}

	@Test
	fun leaseRecoveryAndPayloadCleanupRunOnSeparateSchedules() {
		val dispatcher = JobDispatcher(properties(), jobStore, queueService, metrics)
		dispatcher.recoverExpiredLeases()
		Mockito.verify(jobStore).reapExpiredLeases()
		dispatcher.cleanExpiredPayloads()
		Mockito.verify(jobStore).clearExpiredPayloads(7)
	}

	private fun properties() = JobProperties(true, "http://localhost:4566", "us-east-1", "test", "test", "jobs", "jobs-dlq", 3, 2, 20, 300, 60, 5, 15, 300, 5_000, 30_000, 3_600_000, 120, 7)
}
