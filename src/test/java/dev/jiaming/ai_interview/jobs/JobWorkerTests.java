package dev.jiaming.ai_interview.jobs;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jiaming.ai_interview.gemini.GeminiException;
import dev.jiaming.ai_interview.common.RuntimeModeProperties;
import dev.jiaming.ai_interview.resume.ResumeExtractionException;
import software.amazon.awssdk.services.sqs.model.Message;

class JobWorkerTests {

	private final JobQueueService queueService = mock(JobQueueService.class);

	private final BackgroundJobStore jobStore = mock(BackgroundJobStore.class);

	private final JobProcessor processor = mock(JobProcessor.class);

	private final JobMetrics metrics = mock(JobMetrics.class);

	private final JobProperties properties = new JobProperties(
		true, "http://localhost:4566", "us-east-1", "test", "test", "jobs", "jobs-dlq", 3,
		2, 20, 300, 60, 3, 15, 300, 5_000, 30_000, 3_600_000, 120, 7
	);

	private final JobWorker worker = new JobWorker(
		properties, queueService, jobStore, processor, new JobFailureClassifier(), metrics,
		new RuntimeModeProperties("all"), new JobRetryDelayStrategy(bound -> bound - 1), java.util.List.of()
	);

	private ScheduledExecutorService heartbeatExecutor;

	@BeforeEach
	void setUp() {
		heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
		ReflectionTestUtils.setField(worker, "heartbeatExecutor", heartbeatExecutor);
	}

	@AfterEach
	void tearDown() {
		heartbeatExecutor.shutdownNow();
	}

	@Test
	void duplicateQueueMessageExecutesBusinessLogicOnlyOnce() {
		UUID jobId = UUID.randomUUID();
		Message message = message();
		BackgroundJob processing = job(jobId, JobType.ANALYSIS, JobStatus.PROCESSING, 1, null);
		BackgroundJob succeeded = job(jobId, JobType.ANALYSIS, JobStatus.SUCCEEDED, 1, null);
		when(queueService.parse(message)).thenReturn(new JobMessage(jobId));
		when(queueService.receiveCount(message)).thenReturn(1);
		when(jobStore.claim(eq(jobId), any(), eq(properties.visibilityTimeout())))
			.thenReturn(Optional.of(processing), Optional.empty());
		when(processor.process(eq(processing), any())).thenReturn(new ObjectMapper().createObjectNode());
		when(jobStore.markSucceeded(eq(jobId), any(), any())).thenReturn(true);
		when(jobStore.findById(jobId)).thenReturn(Optional.of(succeeded));

		worker.processMessage(message);
		worker.processMessage(message);

		verify(processor, times(1)).process(eq(processing), any());
		verify(queueService, times(2)).delete(message);
	}

	@Test
	void transientFailureSchedulesFreshRetryAndDeletesCurrentMessage() {
		UUID jobId = UUID.randomUUID();
		Message message = message();
		BackgroundJob processing = job(jobId, JobType.ANALYSIS, JobStatus.PROCESSING, 1, null);
		when(queueService.parse(message)).thenReturn(new JobMessage(jobId));
		when(jobStore.claim(eq(jobId), any(), eq(properties.visibilityTimeout())))
			.thenReturn(Optional.of(processing));
		when(processor.process(eq(processing), any())).thenThrow(new GeminiException("timeout"));
		when(jobStore.markRetrying(eq(jobId), any(), eq("GEMINI_UPSTREAM_ERROR"), eq("timeout"), eq(java.time.Duration.ofSeconds(15))))
			.thenReturn(true);

		worker.processMessage(message);

		verify(queueService).delete(message);
		verify(queueService, never()).changeVisibility(message, 15);
	}

	@Test
	void permanentExtractionFailureIsNotRetried() {
		UUID jobId = UUID.randomUUID();
		Message message = message();
		BackgroundJob processing = job(jobId, JobType.RESUME_EXTRACTION, JobStatus.PROCESSING, 1, null);
		when(queueService.parse(message)).thenReturn(new JobMessage(jobId));
		when(jobStore.claim(eq(jobId), any(), eq(properties.visibilityTimeout())))
			.thenReturn(Optional.of(processing));
		when(processor.process(eq(processing), any())).thenThrow(new ResumeExtractionException("Encrypted PDF"));
		when(jobStore.markFailed(eq(jobId), any(), eq("RESUME_EXTRACTION_FAILED"), eq("Encrypted PDF"), eq(false)))
			.thenReturn(true);

		worker.processMessage(message);

		verify(queueService).delete(message);
		verify(jobStore, never()).markRetrying(any(), any(), any(), any(), any());
	}

	@Test
	void transientFailureStopsAfterThreeAttemptsAndMovesTransportMessageToDlq() {
		UUID jobId = UUID.randomUUID();
		Message message = message();
		BackgroundJob processing = job(jobId, JobType.ANALYSIS, JobStatus.PROCESSING, 3, null);
		when(queueService.parse(message)).thenReturn(new JobMessage(jobId));
		when(jobStore.claim(eq(jobId), any(), eq(properties.visibilityTimeout())))
			.thenReturn(Optional.of(processing));
		when(processor.process(eq(processing), any())).thenThrow(new GeminiException("timeout"));
		when(jobStore.findById(jobId)).thenReturn(Optional.of(processing));
		when(jobStore.markFailed(eq(jobId), any(), eq("RETRIES_EXHAUSTED_GEMINI_UPSTREAM_ERROR"), eq("timeout"), eq(false)))
			.thenReturn(true);

		worker.processMessage(message);

		verify(queueService).sendDeadLetter(jobId);
		verify(queueService).delete(message);
		verify(queueService, never()).changeVisibility(message, 0);
		verify(jobStore, never()).markRetrying(any(), any(), any(), any(), any());
	}

	@Test
	void exhaustedTerminalMessageIsDeadLetteredAfterAWorkerRestart() {
		UUID jobId = UUID.randomUUID();
		Message message = message();
		BackgroundJob failed = job(
			jobId, JobType.ANALYSIS, JobStatus.FAILED, 3, "RETRIES_EXHAUSTED_GEMINI_ERROR"
		);
		when(queueService.parse(message)).thenReturn(new JobMessage(jobId));
		when(jobStore.claim(eq(jobId), any(), eq(properties.visibilityTimeout())))
			.thenReturn(Optional.empty());
		when(jobStore.findById(jobId)).thenReturn(Optional.of(failed));

		worker.processMessage(message);

		verify(queueService).sendDeadLetter(jobId);
		verify(queueService).delete(message);
	}

	@Test
	void failedDeadLetterPublishLeavesTheMainMessageForAnotherAttempt() {
		UUID jobId = UUID.randomUUID();
		Message message = message();
		BackgroundJob processing = job(jobId, JobType.ANALYSIS, JobStatus.PROCESSING, 3, null);
		when(queueService.parse(message)).thenReturn(new JobMessage(jobId));
		when(jobStore.claim(eq(jobId), any(), eq(properties.visibilityTimeout())))
			.thenReturn(Optional.of(processing));
		when(processor.process(eq(processing), any())).thenThrow(new GeminiException("timeout"));
		when(jobStore.findById(jobId)).thenReturn(Optional.of(processing));
		when(jobStore.markFailed(eq(jobId), any(), eq("RETRIES_EXHAUSTED_GEMINI_UPSTREAM_ERROR"), eq("timeout"), eq(false)))
			.thenReturn(true);
		when(queueService.receiveCount(message)).thenReturn(1);
		org.mockito.Mockito.doThrow(new IllegalStateException("DLQ unavailable"))
			.when(queueService).sendDeadLetter(jobId);

		worker.processMessage(message);

		verify(queueService, never()).delete(message);
		verify(queueService).changeVisibility(message, 5);
	}

	@Test
	void staleWorkerDoesNotDeleteMessageAfterLosingLease() {
		UUID jobId = UUID.randomUUID();
		Message message = message();
		BackgroundJob processing = job(jobId, JobType.ANALYSIS, JobStatus.PROCESSING, 1, null);
		when(queueService.parse(message)).thenReturn(new JobMessage(jobId));
		when(jobStore.claim(eq(jobId), any(), eq(properties.visibilityTimeout())))
			.thenReturn(Optional.of(processing));
		when(processor.process(eq(processing), any())).thenReturn(new ObjectMapper().createObjectNode());
		when(jobStore.markSucceeded(eq(jobId), any(), any())).thenReturn(false);

		worker.processMessage(message);

		verify(queueService, never()).delete(message);
	}

	@Test
	void malformedMainQueueMessageIsLeftForSqsRedrive() {
		Message message = message();
		when(queueService.parse(message)).thenThrow(new IllegalArgumentException("bad JSON"));

		worker.processMessage(message);

		verify(metrics).invalidMessage();
		verify(queueService).changeVisibility(message, 0);
		verify(queueService, never()).delete(message);
	}

	@Test
	void terminalResumeFailureRunsCleanupHandler() {
		UUID jobId = UUID.randomUUID();
		Message message = message();
		BackgroundJob processing = job(jobId, JobType.RESUME_EXTRACTION, JobStatus.PROCESSING, 1, null);
		JobTerminalFailureHandler handler = mock(JobTerminalFailureHandler.class);
		when(handler.supports(JobType.RESUME_EXTRACTION)).thenReturn(true);
		JobWorker cleanupWorker = worker(properties, List.of(handler));
		when(queueService.parse(message)).thenReturn(new JobMessage(jobId));
		when(jobStore.claim(eq(jobId), any(), eq(properties.visibilityTimeout())))
			.thenReturn(Optional.of(processing));
		when(processor.process(eq(processing), any())).thenThrow(new ResumeExtractionException("Encrypted PDF"));
		when(jobStore.markFailed(eq(jobId), any(), eq("RESUME_EXTRACTION_FAILED"), eq("Encrypted PDF"), eq(false)))
			.thenReturn(true);

		cleanupWorker.processMessage(message);

		verify(handler).handle(processing, "RESUME_EXTRACTION_FAILED", "Encrypted PDF");
	}

	@Test
	void heartbeatLeaseLossInterruptsProcessingWithoutRecordingFailure() throws Exception {
		JobProperties heartbeatProperties = properties(1, 120);
		JobWorker heartbeatWorker = worker(heartbeatProperties, List.of());
		UUID jobId = UUID.randomUUID();
		Message message = message();
		BackgroundJob processing = job(jobId, JobType.ANALYSIS, JobStatus.PROCESSING, 1, null);
		CountDownLatch entered = new CountDownLatch(1);
		when(queueService.parse(message)).thenReturn(new JobMessage(jobId));
		when(jobStore.claim(eq(jobId), any(), eq(heartbeatProperties.visibilityTimeout())))
			.thenReturn(Optional.of(processing));
		when(jobStore.extendLease(eq(jobId), any(), eq(heartbeatProperties.visibilityTimeout()))).thenReturn(false);
		when(processor.process(eq(processing), any())).thenAnswer(invocation -> {
			entered.countDown();
			try {
				new CountDownLatch(1).await();
				return new ObjectMapper().createObjectNode();
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new RuntimeException("interrupted", exception);
			}
		});
		Thread processingThread = new Thread(() -> heartbeatWorker.processMessage(message));

		processingThread.start();
		assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
		processingThread.join(3_000);

		assertThat(processingThread.isAlive()).isFalse();
		verify(jobStore, never()).markRetrying(any(), any(), any(), any(), any());
		verify(jobStore, never()).markFailed(any(), any(), any(), any(), anyBoolean());
		verify(queueService, never()).delete(message);
	}

	@Test
	void shutdownReleasesUnfinishedJobWithoutConsumingAttempt() throws Exception {
		JobProperties shutdownProperties = properties(10, 1);
		JobWorker shutdownWorker = new JobWorker(
			shutdownProperties, queueService, jobStore, processor,
			new JobFailureClassifier(), metrics, new RuntimeModeProperties("all"),
			new JobRetryDelayStrategy(bound -> bound - 1), List.of()
		);
		UUID jobId = UUID.randomUUID();
		Message message = message();
		BackgroundJob processing = job(jobId, JobType.ANALYSIS, JobStatus.PROCESSING, 1, null);
		CountDownLatch entered = new CountDownLatch(1);
		java.util.concurrent.atomic.AtomicInteger receives = new java.util.concurrent.atomic.AtomicInteger();
		when(queueService.receive(anyInt())).thenAnswer(invocation -> {
			if (receives.getAndIncrement() == 0) {
				return List.of(message);
			}
			Thread.sleep(10);
			return List.of();
		});
		when(queueService.parse(message)).thenReturn(new JobMessage(jobId));
		when(jobStore.claim(eq(jobId), any(), eq(shutdownProperties.visibilityTimeout())))
			.thenReturn(Optional.of(processing));
		when(jobStore.releaseForRedispatch(eq(jobId), any())).thenReturn(true);
		when(processor.process(eq(processing), any())).thenAnswer(invocation -> {
			entered.countDown();
			try {
				new CountDownLatch(1).await();
				return new ObjectMapper().createObjectNode();
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new RuntimeException("interrupted", exception);
			}
		});

		shutdownWorker.start();
		assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
		shutdownWorker.stop();

		verify(jobStore).releaseForRedispatch(eq(jobId), any());
		verify(queueService).delete(message);
		verify(jobStore, never()).markRetrying(any(), any(), any(), any(), any());
		verify(jobStore, never()).markFailed(any(), any(), any(), any(), anyBoolean());
	}

	private JobWorker worker(JobProperties jobProperties, List<JobTerminalFailureHandler> handlers) {
		JobWorker configured = new JobWorker(
			jobProperties, queueService, jobStore, processor,
			new JobFailureClassifier(), metrics, new RuntimeModeProperties("all"),
			new JobRetryDelayStrategy(bound -> bound - 1), handlers
		);
		ReflectionTestUtils.setField(configured, "heartbeatExecutor", heartbeatExecutor);
		return configured;
	}

	private JobProperties properties(int heartbeatSeconds, int shutdownGraceSeconds) {
		return new JobProperties(
			true, "http://localhost:4566", "us-east-1", "test", "test", "jobs", "jobs-dlq", 3,
			2, 1, 30, heartbeatSeconds, 3, 15, 300, 5_000, 30_000, 3_600_000,
			shutdownGraceSeconds, 7
		);
	}

	private Message message() {
		return Message.builder().messageId(UUID.randomUUID().toString()).receiptHandle("receipt").body("{}").build();
	}

	private BackgroundJob job(UUID id, JobType type, JobStatus status, int attempts, String errorCode) {
		Instant now = Instant.now();
		return new BackgroundJob(
			id, UUID.randomUUID(), type, "resource", null, status, JobStage.QUEUED,
			new ObjectMapper().createObjectNode(), null, "fingerprint", attempts, 3,
			errorCode, null, null, now, now, now, now, now,
			status.terminal() ? now : null, UUID.randomUUID(), now.plusSeconds(300)
		);
	}
}
