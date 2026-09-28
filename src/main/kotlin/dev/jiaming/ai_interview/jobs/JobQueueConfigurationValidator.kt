package dev.jiaming.ai_interview.jobs

import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component

@Component
internal class JobQueueConfigurationValidator(private val properties: JobProperties, private val queueService: JobQueueService) : SmartLifecycle {
    private val running = AtomicBoolean(false)
    override fun start() {
        if (!properties.enabled || !running.compareAndSet(false, true)) return
        try {
            queueService.validateConfiguration()
            log.info("job_queue_configuration_valid queue={} dlq={} maxReceiveCount={}", properties.queueName(), properties.dlqName(), properties.maxReceiveCount)
        } catch (exception: RuntimeException) {
            running.set(false)
            throw exception
        }
    }
    override fun stop() { running.set(false) }
    override fun isRunning() = running.get()
    override fun getPhase() = Int.MIN_VALUE + 100
    companion object { private val log = LoggerFactory.getLogger(JobQueueConfigurationValidator::class.java) }
}
