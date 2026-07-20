package dev.jiaming.ai_interview.jobs;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class JobDispatcher {

	private static final Logger log = LoggerFactory.getLogger(JobDispatcher.class);

	private final JobProperties properties;

	private final BackgroundJobStore jobStore;

	private final JobQueueService queueService;

	private final JobMetrics metrics;

	public JobDispatcher(
		JobProperties properties,
		BackgroundJobStore jobStore,
		JobQueueService queueService,
		JobMetrics metrics
	) {
		this.properties = properties;
		this.jobStore = jobStore;
		this.queueService = queueService;
		this.metrics = metrics;
	}

	public boolean dispatch(UUID jobId) {
		if (!properties.enabled()) {
			return false;
		}
		try {
			queueService.send(jobId);
			jobStore.markEnqueued(jobId);
			metrics.dispatched();
			log.info("job_dispatched jobId={}", jobId);
			return true;
		}
		catch (RuntimeException exception) {
			metrics.dispatchFailure();
			log.warn("job_dispatch_failed jobId={} reason={}", jobId, exception.getMessage());
			return false;
		}
	}

	@Scheduled(fixedDelayString = "${app.jobs.dispatch-interval-ms:5000}")
	void recoverUndispatchedJobs() {
		if (!properties.enabled()) {
			return;
		}
		for (UUID jobId : jobStore.findUndispatched(25)) {
			dispatch(jobId);
		}
	}

	@Scheduled(fixedDelayString = "${app.jobs.lease-reaper-interval-ms:30000}")
	void recoverExpiredLeases() {
		if (!properties.enabled()) {
			return;
		}
		int reaped = jobStore.reapExpiredLeases();
		if (reaped > 0) {
			log.warn("job_leases_recovered count={}", reaped);
		}
	}

	@Scheduled(fixedDelayString = "${app.jobs.cleanup-interval-ms:3600000}")
	void cleanExpiredPayloads() {
		if (!properties.enabled()) {
			return;
		}
		int cleaned = jobStore.clearExpiredPayloads(properties.retentionDays());
		if (cleaned > 0) {
			log.info("job_payloads_cleaned count={}", cleaned);
		}
	}
}
