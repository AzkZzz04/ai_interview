package dev.jiaming.ai_interview.jobs;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import dev.jiaming.ai_interview.common.RuntimeModeProperties;
import software.amazon.awssdk.services.sqs.model.Message;

class JobDlqReconcilerTests {

	private final JobQueueService queueService = mock(JobQueueService.class);

	private final BackgroundJobStore jobStore = mock(BackgroundJobStore.class);

	private final JobMetrics metrics = mock(JobMetrics.class);

	private final JobDlqReconciler reconciler = new JobDlqReconciler(
		properties(), queueService, jobStore, metrics, new RuntimeModeProperties("all")
	);

	@Test
	void malformedMessageIsAcknowledgedOnlyAfterItReachesDlq() {
		Message message = message();
		when(queueService.parse(message)).thenThrow(new IllegalArgumentException("bad JSON"));

		reconciler.reconcile(message);

		verify(metrics).invalidDeadLetter();
		verify(queueService).deleteDeadLetter(message);
		verify(queueService, never()).delete(message);
	}

	@Test
	void nonterminalJobIsPreparedForFreshDispatch() {
		UUID jobId = UUID.randomUUID();
		Message message = message();
		BackgroundJob job = job(jobId, JobStatus.RETRYING, 2, 5);
		when(queueService.parse(message)).thenReturn(new JobMessage(jobId));
		when(jobStore.findById(jobId)).thenReturn(Optional.of(job));
		when(jobStore.prepareDlqRecovery(jobId)).thenReturn(true);

		reconciler.reconcile(message);

		verify(metrics).dlqArrival(JobType.ANALYSIS);
		verify(jobStore).prepareDlqRecovery(jobId);
		verify(queueService).deleteDeadLetter(message);
	}

	@Test
	void exhaustedJobIsMadeTerminalBeforeDlqMessageIsDeleted() {
		UUID jobId = UUID.randomUUID();
		Message message = message();
		BackgroundJob job = job(jobId, JobStatus.RETRYING, 5, 5);
		when(queueService.parse(message)).thenReturn(new JobMessage(jobId));
		when(jobStore.findById(jobId)).thenReturn(Optional.of(job));
		when(jobStore.markExhaustedFromDlq(jobId)).thenReturn(true);

		reconciler.reconcile(message);

		verify(jobStore).markExhaustedFromDlq(jobId);
		verify(metrics).retriesExhausted(JobType.ANALYSIS);
		verify(queueService).deleteDeadLetter(message);
		verify(jobStore, never()).prepareDlqRecovery(jobId);
	}

	@Test
	void terminalJobDuplicateIsOnlyAcknowledged() {
		UUID jobId = UUID.randomUUID();
		Message message = message();
		when(queueService.parse(message)).thenReturn(new JobMessage(jobId));
		when(jobStore.findById(jobId)).thenReturn(Optional.of(job(jobId, JobStatus.SUCCEEDED, 1, 3)));

		reconciler.reconcile(message);

		verify(queueService).deleteDeadLetter(message);
		verify(jobStore, never()).prepareDlqRecovery(jobId);
		verify(jobStore, never()).markExhaustedFromDlq(jobId);
	}

	private JobProperties properties() {
		return new JobProperties(
			true, "http://localhost:4566", "us-east-1", "test", "test",
			"jobs", "jobs-dlq", 3, 2, 20, 300, 60, 5,
			15, 300, 5_000, 30_000, 3_600_000, 120, 7
		);
	}

	private Message message() {
		return Message.builder()
			.messageId(UUID.randomUUID().toString())
			.receiptHandle("receipt")
			.body("{}")
			.build();
	}

	private BackgroundJob job(UUID id, JobStatus status, int attempts, int maxAttempts) {
		Instant now = Instant.now();
		return new BackgroundJob(
			id, UUID.randomUUID(), JobType.ANALYSIS, "resume", UUID.randomUUID(), status, JobStage.QUEUED,
			new ObjectMapper().createObjectNode(), null, "fingerprint", attempts, maxAttempts,
			null, null, null, now, now, now, now, now,
			status.terminal() ? now : null, null, null
		);
	}
}
