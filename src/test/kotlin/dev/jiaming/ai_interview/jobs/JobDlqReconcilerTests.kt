package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.common.RuntimeModeProperties
import java.time.Instant
import java.util.Optional
import java.util.UUID
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import software.amazon.awssdk.services.sqs.model.Message

class JobDlqReconcilerTests {
	private val queueService = Mockito.mock(JobQueueService::class.java)
	private val jobStore = Mockito.mock(BackgroundJobStore::class.java)
	private val metrics = Mockito.mock(JobMetrics::class.java)
	private val reconciler = JobDlqReconciler(properties(), queueService, jobStore, metrics, RuntimeModeProperties("all"))

	@Test fun malformedMessageIsAcknowledgedOnlyAfterItReachesDlq() {
		val message = message()
		Mockito.`when`(queueService.parse(message)).thenThrow(IllegalArgumentException("bad JSON"))
		reconciler.reconcile(message)
		Mockito.verify(metrics).invalidDeadLetter()
		Mockito.verify(queueService).deleteDeadLetter(message)
		Mockito.verify(queueService, Mockito.never()).delete(message)
	}

	@Test fun nonterminalJobIsPreparedForFreshDispatch() {
		val jobId = UUID.randomUUID(); val message = message(); val job = job(jobId, JobStatus.RETRYING, 2, 5)
		Mockito.`when`(queueService.parse(message)).thenReturn(JobMessage(jobId))
		Mockito.`when`(jobStore.findById(jobId)).thenReturn(Optional.of(job))
		Mockito.`when`(jobStore.prepareDlqRecovery(jobId)).thenReturn(true)
		reconciler.reconcile(message)
		Mockito.verify(metrics).dlqArrival(JobType.ANALYSIS)
		Mockito.verify(jobStore).prepareDlqRecovery(jobId)
		Mockito.verify(queueService).deleteDeadLetter(message)
	}

	@Test fun exhaustedJobIsMadeTerminalBeforeDlqMessageIsDeleted() {
		val jobId = UUID.randomUUID(); val message = message(); val job = job(jobId, JobStatus.RETRYING, 5, 5)
		Mockito.`when`(queueService.parse(message)).thenReturn(JobMessage(jobId))
		Mockito.`when`(jobStore.findById(jobId)).thenReturn(Optional.of(job))
		Mockito.`when`(jobStore.markExhaustedFromDlq(jobId)).thenReturn(true)
		reconciler.reconcile(message)
		Mockito.verify(jobStore).markExhaustedFromDlq(jobId)
		Mockito.verify(metrics).retriesExhausted(JobType.ANALYSIS)
		Mockito.verify(queueService).deleteDeadLetter(message)
		Mockito.verify(jobStore, Mockito.never()).prepareDlqRecovery(jobId)
	}

	@Test fun terminalJobDuplicateIsOnlyAcknowledged() {
		val jobId = UUID.randomUUID(); val message = message()
		Mockito.`when`(queueService.parse(message)).thenReturn(JobMessage(jobId))
		Mockito.`when`(jobStore.findById(jobId)).thenReturn(Optional.of(job(jobId, JobStatus.SUCCEEDED, 1, 3)))
		reconciler.reconcile(message)
		Mockito.verify(queueService).deleteDeadLetter(message)
		Mockito.verify(jobStore, Mockito.never()).prepareDlqRecovery(jobId)
		Mockito.verify(jobStore, Mockito.never()).markExhaustedFromDlq(jobId)
	}

	private fun properties() = JobProperties(true, "http://localhost:4566", "us-east-1", "test", "test", "jobs", "jobs-dlq", 3, 2, 20, 300, 60, 5, 15, 300, 5_000, 30_000, 3_600_000, 120, 7)
	private fun message() = Message.builder().messageId(UUID.randomUUID().toString()).receiptHandle("receipt").body("{}").build()
	private fun job(id: UUID, status: JobStatus, attempts: Int, maxAttempts: Int): BackgroundJob {
		val now = Instant.now()
		return BackgroundJob(id, UUID.randomUUID(), JobType.ANALYSIS, "resume", UUID.randomUUID(), status, JobStage.QUEUED, ObjectMapper().createObjectNode(), null, "fingerprint", attempts, maxAttempts, null, null, null, now, now, now, now, now, if (status.terminal()) now else null, null, null)
	}
}
