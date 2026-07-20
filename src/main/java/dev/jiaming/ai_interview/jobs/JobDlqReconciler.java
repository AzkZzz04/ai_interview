package dev.jiaming.ai_interview.jobs;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.sqs.model.Message;

@Component
class JobDlqReconciler implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(JobDlqReconciler.class);

	private final JobProperties properties;

	private final JobQueueService queueService;

	private final BackgroundJobStore jobStore;

	private final JobMetrics metrics;

	private final AtomicBoolean running = new AtomicBoolean(false);

	private ExecutorService executor;

	JobDlqReconciler(
		JobProperties properties,
		JobQueueService queueService,
		BackgroundJobStore jobStore,
		JobMetrics metrics
	) {
		this.properties = properties;
		this.queueService = queueService;
		this.jobStore = jobStore;
		this.metrics = metrics;
	}

	@Override
	public void start() {
		if (!properties.workerEnabled() || !running.compareAndSet(false, true)) {
			return;
		}
		executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "job-dlq-reconciler");
			thread.setDaemon(true);
			return thread;
		});
		executor.submit(this::receiveLoop);
	}

	@Override
	public void stop() {
		if (!running.compareAndSet(true, false) || executor == null) {
			return;
		}
		executor.shutdownNow();
		try {
			executor.awaitTermination(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public boolean isRunning() {
		return running.get();
	}

	@Override
	public int getPhase() {
		return Integer.MAX_VALUE - 90;
	}

	void reconcile(Message message) {
		JobMessage queued;
		try {
			queued = queueService.parse(message);
		}
		catch (RuntimeException exception) {
			metrics.invalidDeadLetter();
			log.error("job_dlq_message_invalid messageId={} reason={}", message.messageId(), exception.getMessage());
			queueService.deleteDeadLetter(message);
			return;
		}

		Optional<BackgroundJob> found = jobStore.findById(queued.jobId());
		if (found.isEmpty()) {
			log.warn("job_dlq_message_orphaned jobId={} messageId={}", queued.jobId(), message.messageId());
			queueService.deleteDeadLetter(message);
			return;
		}

		BackgroundJob job = found.get();
		metrics.dlqArrival(job.jobType());
		if (job.status().terminal()) {
			queueService.deleteDeadLetter(message);
			return;
		}

		if (job.attempts() >= job.maxAttempts()) {
			if (jobStore.markExhaustedFromDlq(job.id())) {
				metrics.failed(job.jobType(), true);
				metrics.retriesExhausted(job.jobType());
				log.error("job_dlq_exhausted jobId={} type={} attempts={}",
					job.id(), job.jobType(), job.attempts());
			}
			queueService.deleteDeadLetter(message);
			return;
		}

		boolean recovered = jobStore.prepareDlqRecovery(job.id());
		queueService.deleteDeadLetter(message);
		log.warn("job_dlq_reconciled jobId={} type={} redispatchPrepared={}",
			job.id(), job.jobType(), recovered);
	}

	private void receiveLoop() {
		while (running.get()) {
			try {
				List<Message> messages = queueService.receiveDeadLetters(10);
				for (Message message : messages) {
					try {
						reconcile(message);
					}
					catch (RuntimeException exception) {
						log.warn("job_dlq_reconcile_failed messageId={} reason={}",
							message.messageId(), exception.getMessage());
						queueService.changeDeadLetterVisibility(message, 5);
					}
				}
			}
			catch (RuntimeException exception) {
				if (running.get()) {
					log.warn("job_dlq_receive_failed reason={}", exception.getMessage());
					sleep(1_000);
				}
			}
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
}
