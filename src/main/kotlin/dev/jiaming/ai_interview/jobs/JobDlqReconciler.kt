package dev.jiaming.ai_interview.jobs

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import dev.jiaming.ai_interview.common.RuntimeModeProperties
import software.amazon.awssdk.services.sqs.model.Message

@Component
internal class JobDlqReconciler(private val properties: JobProperties, private val queueService: JobQueueService,
                                private val jobStore: BackgroundJobStore, private val metrics: JobMetrics,
                                private val runtimeMode: RuntimeModeProperties) : SmartLifecycle {
    private val running = AtomicBoolean(false)
    private var executor: java.util.concurrent.ExecutorService? = null
    override fun start() {
        if (!properties.enabled || !runtimeMode.workerEnabled() || !running.compareAndSet(false, true)) return
        executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "job-dlq-reconciler").apply { isDaemon = true } }
        executor!!.submit(::receiveLoop)
    }
    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        val current = executor ?: return
        current.shutdownNow()
        try { current.awaitTermination(5, TimeUnit.SECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }
    override fun isRunning() = running.get()
    override fun getPhase() = Int.MAX_VALUE - 90
    internal fun reconcile(message: Message) {
        val queued = try { queueService.parse(message) } catch (exception: RuntimeException) {
            metrics.invalidDeadLetter(); log.error("job_dlq_message_invalid messageId={} reason={}", message.messageId(), exception.message)
            queueService.deleteDeadLetter(message); return
        }
        val job = jobStore.findById(queued.jobId).orElse(null)
        if (job == null) { log.warn("job_dlq_message_orphaned jobId={} messageId={}", queued.jobId, message.messageId()); queueService.deleteDeadLetter(message); return }
        metrics.dlqArrival(job.jobType)
        if (job.status.terminal()) { queueService.deleteDeadLetter(message); return }
        if (job.attempts >= job.maxAttempts) {
            if (jobStore.markExhaustedFromDlq(job.id)) {
                metrics.failed(job.jobType, true); metrics.retriesExhausted(job.jobType)
                log.error("job_dlq_exhausted jobId={} type={} attempts={}", job.id, job.jobType, job.attempts)
            }
            queueService.deleteDeadLetter(message); return
        }
        val recovered = jobStore.prepareDlqRecovery(job.id)
        queueService.deleteDeadLetter(message)
        log.warn("job_dlq_reconciled jobId={} type={} redispatchPrepared={}", job.id, job.jobType, recovered)
    }
    private fun receiveLoop() {
        while (running.get()) try {
            queueService.receiveDeadLetters(10).forEach { message ->
                try { reconcile(message) } catch (exception: RuntimeException) {
                    log.warn("job_dlq_reconcile_failed messageId={} reason={}", message.messageId(), exception.message)
                    queueService.changeDeadLetterVisibility(message, 5)
                }
            }
        } catch (exception: RuntimeException) {
            if (running.get()) { log.warn("job_dlq_receive_failed reason={}", exception.message); sleep(1_000) }
        }
    }
    private fun sleep(millis: Long) { try { Thread.sleep(millis) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); running.set(false) } }
    companion object { private val log = LoggerFactory.getLogger(JobDlqReconciler::class.java) }
}
