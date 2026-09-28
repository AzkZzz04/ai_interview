package dev.jiaming.ai_interview.jobs

import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class JobDispatcher(
    private val properties: JobProperties,
    private val jobStore: BackgroundJobStore,
    private val queueService: JobQueueService,
    private val metrics: JobMetrics
) {
    fun dispatch(jobId: UUID): Boolean {
        if (!properties.enabled) return false
        try {
            queueService.send(jobId)
            jobStore.markEnqueued(jobId)
            metrics.dispatched()
            log.info("job_dispatched jobId={}", jobId)
            return true
        } catch (exception: RuntimeException) {
            metrics.dispatchFailure()
            log.warn("job_dispatch_failed jobId={} reason={}", jobId, exception.message)
            return false
        }
    }

    @Scheduled(fixedDelayString = "\${app.jobs.dispatch-interval-ms:5000}")
    fun recoverUndispatchedJobs() {
        if (properties.enabled) jobStore.findUndispatched(25).forEach { dispatch(it) }
    }

    @Scheduled(fixedDelayString = "\${app.jobs.lease-reaper-interval-ms:30000}")
    fun recoverExpiredLeases() {
        if (!properties.enabled) return
        val reaped = jobStore.reapExpiredLeases()
        if (reaped > 0) log.warn("job_leases_recovered count={}", reaped)
    }

    @Scheduled(fixedDelayString = "\${app.jobs.cleanup-interval-ms:3600000}")
    fun cleanExpiredPayloads() {
        if (!properties.enabled) return
        val cleaned = jobStore.clearExpiredPayloads(properties.retentionDays)
        if (cleaned > 0) log.info("job_payloads_cleaned count={}", cleaned)
    }

    companion object { private val log = LoggerFactory.getLogger(JobDispatcher::class.java) }
}
