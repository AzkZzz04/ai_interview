package dev.jiaming.ai_interview.jobs;

import java.time.Duration;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class JobMetrics {

	private final MeterRegistry meterRegistry;

	public JobMetrics(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	public void submitted(JobType type) {
		meterRegistry.counter("app.jobs.submitted", "type", type.name()).increment();
	}

	public void completed(JobType type, JobStatus status, Duration duration) {
		meterRegistry.counter("app.jobs.completed", "type", type.name(), "status", status.name()).increment();
		meterRegistry.timer("app.jobs.duration", "type", type.name(), "status", status.name()).record(duration);
	}

	public void retried(JobType type) {
		meterRegistry.counter("app.jobs.retried", "type", type.name()).increment();
	}

	public void failed(JobType type, boolean retriesExhausted) {
		meterRegistry.counter("app.jobs.failed", "type", type.name(), "retriesExhausted",
			String.valueOf(retriesExhausted)).increment();
	}

	public void invalidMessage() {
		meterRegistry.counter("app.jobs.queue.invalid_messages").increment();
	}

	public void dispatchFailure() {
		meterRegistry.counter("app.jobs.dispatch.failures").increment();
	}

	public void dispatched() {
		meterRegistry.counter("app.jobs.dispatch.completed").increment();
	}

	public void retriesExhausted(JobType type) {
		meterRegistry.counter("app.jobs.retries.exhausted", "type", type.name()).increment();
	}

	public void dlqArrival(JobType type) {
		meterRegistry.counter("app.jobs.dlq.arrivals", "type", type.name()).increment();
	}

	public void invalidDeadLetter() {
		meterRegistry.counter("app.jobs.dlq.invalid_messages").increment();
	}
}
