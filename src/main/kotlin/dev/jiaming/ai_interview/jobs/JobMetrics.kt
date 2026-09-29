package dev.jiaming.ai_interview.jobs

import java.time.Duration
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class JobMetrics(private val meterRegistry: MeterRegistry) {
    fun submitted(type: JobType) { meterRegistry.counter("app.jobs.submitted", "type", type.name).increment() }
    fun completed(type: JobType, status: JobStatus, duration: Duration) {
        meterRegistry.counter("app.jobs.completed", "type", type.name, "status", status.name).increment()
        meterRegistry.timer("app.jobs.duration", "type", type.name, "status", status.name).record(duration)
    }
    fun queueLag(type: JobType, duration: Duration) { meterRegistry.timer("app.jobs.queue.lag", "type", type.name).record(duration) }
    fun stageDuration(type: JobType, stage: JobStage, duration: Duration) {
        meterRegistry.timer("app.jobs.stage.duration", "type", type.name, "stage", stage.name).record(duration)
    }
    fun retried(type: JobType) { meterRegistry.counter("app.jobs.retried", "type", type.name).increment() }
    fun failed(type: JobType, retriesExhausted: Boolean) {
        meterRegistry.counter("app.jobs.failed", "type", type.name, "retriesExhausted", retriesExhausted.toString()).increment()
    }
    fun invalidMessage() { meterRegistry.counter("app.jobs.queue.invalid_messages").increment() }
    fun dispatchFailure() { meterRegistry.counter("app.jobs.dispatch.failures").increment() }
    fun dispatched() { meterRegistry.counter("app.jobs.dispatch.completed").increment() }
    fun retriesExhausted(type: JobType) { meterRegistry.counter("app.jobs.retries.exhausted", "type", type.name).increment() }
    fun dlqArrival(type: JobType) { meterRegistry.counter("app.jobs.dlq.arrivals", "type", type.name).increment() }
    fun invalidDeadLetter() { meterRegistry.counter("app.jobs.dlq.invalid_messages").increment() }
}
