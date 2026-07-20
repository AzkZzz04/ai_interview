package dev.jiaming.ai_interview.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class JobDispatcherTests {

	private final BackgroundJobStore jobStore = mock(BackgroundJobStore.class);

	private final JobQueueService queueService = mock(JobQueueService.class);

	private final JobMetrics metrics = mock(JobMetrics.class);

	@Test
	void workerRuntimeCanDispatchDueRetryMessages() {
		JobDispatcher dispatcher = new JobDispatcher(properties("worker"), jobStore, queueService, metrics);
		UUID jobId = UUID.randomUUID();

		assertThat(dispatcher.dispatch(jobId)).isTrue();

		verify(queueService).send(jobId);
		verify(jobStore).markEnqueued(jobId);
	}

	@Test
	void recoveryPublishesEveryDueUndispatchedJob() {
		JobDispatcher dispatcher = new JobDispatcher(properties("all"), jobStore, queueService, metrics);
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		when(jobStore.findUndispatched(25)).thenReturn(List.of(first, second));

		dispatcher.recoverUndispatchedJobs();

		verify(queueService).send(first);
		verify(queueService).send(second);
		verify(jobStore).markEnqueued(first);
		verify(jobStore).markEnqueued(second);
	}

	@Test
	void leaseRecoveryAndPayloadCleanupRunOnSeparateSchedules() {
		JobDispatcher dispatcher = new JobDispatcher(properties("all"), jobStore, queueService, metrics);

		dispatcher.recoverExpiredLeases();
		verify(jobStore).reapExpiredLeases();

		dispatcher.cleanExpiredPayloads();
		verify(jobStore).clearExpiredPayloads(7);
	}

	private JobProperties properties(String runtimeMode) {
		return new JobProperties(
			true, runtimeMode, "http://localhost:4566", "us-east-1", "test", "test",
			"jobs", "jobs-dlq", 3, 2, 20, 300, 60, 5,
			15, 300, 5_000, 30_000, 3_600_000, 120, 7
		);
	}
}
