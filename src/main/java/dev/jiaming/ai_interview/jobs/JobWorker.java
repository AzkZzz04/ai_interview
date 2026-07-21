package dev.jiaming.ai_interview.jobs;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import dev.jiaming.ai_interview.common.RuntimeModeProperties;
import software.amazon.awssdk.services.sqs.model.Message;

@Component
public class JobWorker implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

	private final JobProperties properties;

	private final JobQueueService queueService;

	private final BackgroundJobStore jobStore;

	private final JobProcessor processor;

	private final JobFailureClassifier failureClassifier;

	private final JobMetrics metrics;

	private final RuntimeModeProperties runtimeMode;

	private final JobRetryDelayStrategy retryDelayStrategy;

	private final List<JobTerminalFailureHandler> terminalFailureHandlers;

	private final AtomicBoolean running = new AtomicBoolean(false);

	private final ConcurrentMap<UUID, ActiveExecution> activeExecutions = new ConcurrentHashMap<>();

	private ExecutorService receiverExecutor;

	private ExecutorService processingExecutor;

	private ScheduledExecutorService heartbeatExecutor;

	private Semaphore capacity;

	public JobWorker(
		JobProperties properties,
		JobQueueService queueService,
		BackgroundJobStore jobStore,
		JobProcessor processor,
		JobFailureClassifier failureClassifier,
		JobMetrics metrics,
		RuntimeModeProperties runtimeMode,
		JobRetryDelayStrategy retryDelayStrategy,
		List<JobTerminalFailureHandler> terminalFailureHandlers
	) {
		this.properties = properties;
		this.queueService = queueService;
		this.jobStore = jobStore;
		this.processor = processor;
		this.failureClassifier = failureClassifier;
		this.metrics = metrics;
		this.runtimeMode = runtimeMode;
		this.retryDelayStrategy = retryDelayStrategy;
		this.terminalFailureHandlers = List.copyOf(terminalFailureHandlers);
	}

	@Override
	public void start() {
		if (!properties.enabled() || !runtimeMode.workerEnabled() || !running.compareAndSet(false, true)) {
			return;
		}
		capacity = new Semaphore(properties.workerConcurrency());
		receiverExecutor = Executors.newSingleThreadExecutor(runnable -> daemonThread(runnable, "job-receiver"));
		processingExecutor = Executors.newFixedThreadPool(
			properties.workerConcurrency(),
			runnable -> daemonThread(runnable, "job-processor")
		);
		heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(
			runnable -> daemonThread(runnable, "job-heartbeat")
		);
		receiverExecutor.submit(this::receiveLoop);
		log.info("job_worker_started concurrency={} visibilitySeconds={} heartbeatSeconds={}",
			properties.workerConcurrency(),
			properties.visibilityTimeoutSeconds(),
			properties.heartbeatSeconds());
	}

	@Override
	public void stop() {
		if (!running.compareAndSet(true, false)) {
			return;
		}

		shutdownNow(receiverExecutor, 5);
		processingExecutor.shutdown();
		boolean drained = await(processingExecutor, properties.shutdownGraceSeconds());
		if (!drained) {
			log.warn("job_worker_drain_timed_out active={} graceSeconds={}",
				activeExecutions.size(), properties.shutdownGraceSeconds());
			for (ActiveExecution execution : List.copyOf(activeExecutions.values())) {
				releaseForShutdown(execution);
			}
			processingExecutor.shutdownNow();
			await(processingExecutor, 5);
		}
		shutdownNow(heartbeatExecutor, 5);
		log.info("job_worker_stopped remainingActive={}", activeExecutions.size());
	}

	@Override
	public boolean isRunning() {
		return running.get();
	}

	@Override
	public boolean isAutoStartup() {
		return true;
	}

	@Override
	public int getPhase() {
		return Integer.MAX_VALUE - 100;
	}

	private void receiveLoop() {
		while (running.get()) {
			try {
				int available = capacity.availablePermits();
				if (available == 0) {
					sleep(100);
					continue;
				}
				List<Message> messages = queueService.receive(available);
				for (Message message : messages) {
					if (!running.get()) {
						queueService.changeVisibility(message, 0);
						continue;
					}
					if (!capacity.tryAcquire()) {
						queueService.changeVisibility(message, 0);
						continue;
					}
					processingExecutor.submit(() -> {
						try {
							processMessage(message);
						}
						finally {
							capacity.release();
						}
					});
				}
			}
			catch (RuntimeException exception) {
				if (running.get()) {
					log.warn("job_receive_failed reason={}", exception.getMessage());
					sleep(1_000);
				}
			}
		}
	}

	void processMessage(Message message) {
		JobMessage queued;
		try {
			queued = queueService.parse(message);
		}
		catch (RuntimeException exception) {
			metrics.invalidMessage();
			log.error("job_message_invalid messageId={} reason={}", message.messageId(), exception.getMessage());
			queueService.changeVisibility(message, 0);
			return;
		}

		UUID jobId = queued.jobId();
		UUID leaseToken = UUID.randomUUID();
		Optional<BackgroundJob> claimed = jobStore.claim(jobId, leaseToken, properties.visibilityTimeout());
		if (claimed.isEmpty()) {
			handleUnclaimed(message, jobId);
			return;
		}

		BackgroundJob job = claimed.get();
		ActiveExecution execution = new ActiveExecution(message, job, leaseToken, Thread.currentThread());
		activeExecutions.put(job.id(), execution);
		ScheduledFuture<?> heartbeat = startHeartbeat(execution);
		execution.heartbeat(heartbeat);
		Instant started = Instant.now();
		metrics.queueLag(job.jobType(), Duration.between(job.createdAt(), started));
		log.info("job_started jobId={} type={} attempt={} receiveCount={}",
			job.id(), job.jobType(), job.attempts(), queueService.receiveCount(message));
		try {
			JsonNode result = processor.process(job, leaseToken);
			if (execution.leaseLost()) {
				throw new JobLeaseLostException(job.id());
			}
			if (!jobStore.markSucceeded(job.id(), leaseToken, result)) {
				execution.loseLease();
				log.warn("job_completion_ignored_after_lease_loss jobId={} type={}", job.id(), job.jobType());
				return;
			}
			metrics.completed(job.jobType(), JobStatus.SUCCEEDED, Duration.between(started, Instant.now()));
			queueService.delete(message);
			log.info("job_completed jobId={} type={} attempt={}", job.id(), job.jobType(), job.attempts());
		}
		catch (JobLeaseLostException exception) {
			log.warn("job_processing_stopped_after_lease_loss jobId={} type={}", job.id(), job.jobType());
		}
		catch (RuntimeException exception) {
			if (execution.leaseLost()) {
				log.warn("job_processing_abandoned_after_lease_loss jobId={} type={}", job.id(), job.jobType());
			}
			else {
				handleFailure(message, job, leaseToken, started, exception);
			}
		}
		finally {
			heartbeat.cancel(false);
			activeExecutions.remove(job.id(), execution);
		}
	}

	private void handleFailure(
		Message message,
		BackgroundJob job,
		UUID leaseToken,
		Instant started,
		RuntimeException exception
	) {
		JobFailure failure = failureClassifier.classify(exception);
		boolean partial = job.jobType() == JobType.ANALYSIS && hasAssessmentCheckpoint(job.id());
		if (!failure.retryable()) {
			if (!jobStore.markFailed(job.id(), leaseToken, failure.code(), failure.message(), partial)) {
				log.warn("job_failure_ignored_after_lease_loss jobId={}", job.id());
				return;
			}
			notifyTerminalFailure(job, failure);
			metrics.failed(job.jobType(), false);
			metrics.completed(job.jobType(), partial ? JobStatus.PARTIAL : JobStatus.FAILED,
				Duration.between(started, Instant.now()));
			queueService.delete(message);
			log.warn("job_failed jobId={} type={} retryable=false code={} partial={} reason={}",
				job.id(), job.jobType(), failure.code(), partial, failure.message());
			return;
		}

		if (job.attempts() >= job.maxAttempts()) {
			String code = "RETRIES_EXHAUSTED_" + failure.code();
			JobFailure exhausted = new JobFailure(code, failure.message(), false);
			if (!jobStore.markFailed(job.id(), leaseToken, code, failure.message(), partial)) {
				log.warn("job_failure_ignored_after_lease_loss jobId={}", job.id());
				return;
			}
			notifyTerminalFailure(job, exhausted);
			metrics.failed(job.jobType(), true);
			metrics.retriesExhausted(job.jobType());
			metrics.completed(job.jobType(), partial ? JobStatus.PARTIAL : JobStatus.FAILED,
				Duration.between(started, Instant.now()));
			deadLetterExhausted(message, job);
			log.error("job_retries_exhausted jobId={} type={} attempts={} partial={} reason={}",
				job.id(), job.jobType(), job.attempts(), partial, failure.message());
			return;
		}

		Duration delay = retryDelayStrategy.delay(job.attempts(), properties.retryBaseSeconds());
		if (!jobStore.markRetrying(job.id(), leaseToken, failure.code(), failure.message(), delay)) {
			log.warn("job_retry_ignored_after_lease_loss jobId={}", job.id());
			return;
		}
		metrics.retried(job.jobType());
		queueService.delete(message);
		log.warn("job_retry_scheduled jobId={} type={} attempt={} delaySeconds={} code={} reason={}",
			job.id(), job.jobType(), job.attempts(), delay.toSeconds(), failure.code(), failure.message());
	}

	private void handleUnclaimed(Message message, UUID jobId) {
		Optional<BackgroundJob> current = jobStore.findById(jobId);
		if (current.isEmpty()) {
			queueService.delete(message);
			return;
		}
		BackgroundJob job = current.get();
		if (job.status().terminal()) {
			if (retryExhausted(job)) {
				deadLetterExhausted(message, job);
			}
			else {
				queueService.delete(message);
			}
			return;
		}
		if (job.status() == JobStatus.RETRYING && job.enqueuedAt() == null) {
			queueService.delete(message);
			return;
		}

		long delaySeconds = job.status() == JobStatus.PROCESSING || job.runAfter() == null
			? properties.heartbeatSeconds()
			: Math.max(1, Duration.between(Instant.now(), job.runAfter()).toSeconds());
		queueService.changeVisibility(message,
			Math.toIntExact(Math.min(delaySeconds, properties.visibilityTimeoutSeconds())));
	}

	private void deadLetterExhausted(Message message, BackgroundJob job) {
		try {
			queueService.sendDeadLetter(job.id());
			queueService.delete(message);
			log.error("job_dead_lettered jobId={} type={} attempts={}",
				job.id(), job.jobType(), job.attempts());
		}
		catch (RuntimeException exception) {
			log.warn("job_dead_letter_publish_failed jobId={} type={} reason={}",
				job.id(), job.jobType(), exception.getMessage());
			try {
				queueService.changeVisibility(message, Math.min(5, properties.visibilityTimeoutSeconds()));
			}
			catch (RuntimeException visibilityException) {
				log.warn("job_dead_letter_retry_visibility_failed jobId={} reason={}",
					job.id(), visibilityException.getMessage());
			}
		}
	}

	private boolean retryExhausted(BackgroundJob job) {
		return job.errorCode() != null && job.errorCode().startsWith("RETRIES_EXHAUSTED_");
	}

	private ScheduledFuture<?> startHeartbeat(ActiveExecution execution) {
		return heartbeatExecutor.scheduleAtFixedRate(() -> {
			try {
				boolean extended = jobStore.extendLease(
					execution.job().id(), execution.leaseToken(), properties.visibilityTimeout()
				);
				if (!extended) {
					execution.loseLeaseAndInterrupt();
					log.warn("job_heartbeat_lease_lost jobId={}", execution.job().id());
					return;
				}
				queueService.changeVisibility(execution.message(), properties.visibilityTimeoutSeconds());
				log.debug("job_heartbeat jobId={}", execution.job().id());
			}
			catch (RuntimeException exception) {
				log.warn("job_heartbeat_failed jobId={} reason={}",
					execution.job().id(), exception.getMessage());
			}
		}, properties.heartbeatSeconds(), properties.heartbeatSeconds(), TimeUnit.SECONDS);
	}

	private void releaseForShutdown(ActiveExecution execution) {
		boolean released = jobStore.releaseForRedispatch(execution.job().id(), execution.leaseToken());
		execution.loseLeaseAndInterrupt();
		if (!released) {
			log.warn("job_shutdown_release_ignored_after_lease_loss jobId={}", execution.job().id());
			return;
		}
		try {
			queueService.delete(execution.message());
		}
		catch (RuntimeException exception) {
			log.warn("job_shutdown_message_delete_failed jobId={} reason={}",
				execution.job().id(), exception.getMessage());
		}
		log.info("job_released_for_shutdown jobId={} type={}", execution.job().id(), execution.job().jobType());
	}

	private void notifyTerminalFailure(BackgroundJob job, JobFailure failure) {
		for (JobTerminalFailureHandler handler : terminalFailureHandlers) {
			if (!handler.supports(job.jobType())) {
				continue;
			}
			try {
				handler.handle(job, failure.code(), failure.message());
			}
			catch (RuntimeException exception) {
				log.error("job_terminal_cleanup_failed jobId={} type={} reason={}",
					job.id(), job.jobType(), exception.getMessage());
			}
		}
	}

	private boolean hasAssessmentCheckpoint(UUID jobId) {
		return jobStore.findById(jobId)
			.map(BackgroundJob::resultPayload)
			.filter(result -> result != null && result.hasNonNull("assessment"))
			.isPresent();
	}

	private Thread daemonThread(Runnable runnable, String name) {
		Thread thread = new Thread(runnable, name);
		thread.setDaemon(true);
		return thread;
	}

	private void shutdownNow(ExecutorService executor, int timeoutSeconds) {
		if (executor == null) {
			return;
		}
		executor.shutdownNow();
		await(executor, timeoutSeconds);
	}

	private boolean await(ExecutorService executor, int timeoutSeconds) {
		try {
			return executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			running.set(false);
		}
	}

	private static final class ActiveExecution {

		private final Message message;

		private final BackgroundJob job;

		private final UUID leaseToken;

		private final Thread processingThread;

		private final AtomicBoolean leaseLost = new AtomicBoolean(false);

		private final AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();

		private ActiveExecution(Message message, BackgroundJob job, UUID leaseToken, Thread processingThread) {
			this.message = message;
			this.job = job;
			this.leaseToken = leaseToken;
			this.processingThread = processingThread;
		}

		private Message message() {
			return message;
		}

		private BackgroundJob job() {
			return job;
		}

		private UUID leaseToken() {
			return leaseToken;
		}

		private boolean leaseLost() {
			return leaseLost.get();
		}

		private void loseLease() {
			leaseLost.set(true);
		}

		private void loseLeaseAndInterrupt() {
			loseLease();
			ScheduledFuture<?> scheduled = heartbeat.get();
			if (scheduled != null) {
				scheduled.cancel(false);
			}
			processingThread.interrupt();
		}

		private void heartbeat(ScheduledFuture<?> scheduled) {
			heartbeat.set(scheduled);
		}
	}
}
