package dev.jiaming.ai_interview.jobs;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
class JobQueueConfigurationValidator implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(JobQueueConfigurationValidator.class);

	private final JobProperties properties;

	private final JobQueueService queueService;

	private final AtomicBoolean running = new AtomicBoolean(false);

	JobQueueConfigurationValidator(JobProperties properties, JobQueueService queueService) {
		this.properties = properties;
		this.queueService = queueService;
	}

	@Override
	public void start() {
		if (!properties.enabled() || !running.compareAndSet(false, true)) {
			return;
		}
		try {
			queueService.validateConfiguration();
			log.info("job_queue_configuration_valid queue={} dlq={} maxReceiveCount={}",
				properties.queueName(), properties.dlqName(), properties.maxReceiveCount());
		}
		catch (RuntimeException exception) {
			running.set(false);
			throw exception;
		}
	}

	@Override
	public void stop() {
		running.set(false);
	}

	@Override
	public boolean isRunning() {
		return running.get();
	}

	@Override
	public int getPhase() {
		return Integer.MIN_VALUE + 100;
	}
}
