package dev.jiaming.ai_interview.jobs

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import dev.jiaming.ai_interview.common.RuntimeModeProperties
import software.amazon.awssdk.services.sqs.model.Message

@Component
class JobWorker internal constructor(
    private val properties: JobProperties, private val queueService: JobQueueService,
    private val jobStore: BackgroundJobStore, private val processor: JobProcessor,
    private val failureClassifier: JobFailureClassifier, private val metrics: JobMetrics,
    private val runtimeMode: RuntimeModeProperties, private val retryDelayStrategy: JobRetryDelayStrategy,
    terminalFailureHandlers: List<JobTerminalFailureHandler>
) : SmartLifecycle {
    private val terminalFailureHandlers = terminalFailureHandlers.toList()
    private val running = AtomicBoolean(false)
    private val activeExecutions: ConcurrentMap<UUID, ActiveExecution> = ConcurrentHashMap()
    private var receiverExecutor: ExecutorService? = null
    private var processingExecutor: ExecutorService? = null
    private var heartbeatExecutor: ScheduledExecutorService? = null
    private var capacity: Semaphore? = null

    override fun start() {
        if (!properties.enabled || !runtimeMode.workerEnabled() || !running.compareAndSet(false, true)) return
        capacity = Semaphore(properties.workerConcurrency)
        receiverExecutor = Executors.newSingleThreadExecutor { daemonThread(it, "job-receiver") }
        processingExecutor = Executors.newFixedThreadPool(properties.workerConcurrency) { daemonThread(it, "job-processor") }
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor { daemonThread(it, "job-heartbeat") }
        receiverExecutor!!.submit(::receiveLoop)
        log.info("job_worker_started concurrency={} visibilitySeconds={} heartbeatSeconds={}", properties.workerConcurrency, properties.visibilityTimeoutSeconds, properties.heartbeatSeconds)
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        shutdownNow(receiverExecutor, 5)
        val processorExecutor = processingExecutor ?: return
        processorExecutor.shutdown()
        val drained = await(processorExecutor, properties.shutdownGraceSeconds)
        if (!drained) {
            log.warn("job_worker_drain_timed_out active={} graceSeconds={}", activeExecutions.size, properties.shutdownGraceSeconds)
            activeExecutions.values.toList().forEach(::releaseForShutdown)
            processorExecutor.shutdownNow()
            await(processorExecutor, 5)
        }
        shutdownNow(heartbeatExecutor, 5)
        log.info("job_worker_stopped remainingActive={}", activeExecutions.size)
    }
    override fun isRunning() = running.get()
    override fun isAutoStartup() = true
    override fun getPhase() = Int.MAX_VALUE - 100

    private fun receiveLoop() {
        while (running.get()) try {
            val available = capacity!!.availablePermits()
            if (available == 0) { sleep(100); continue }
            val messages = queueService.receive(available)
            for (message in messages) {
                if (!running.get()) { queueService.changeVisibility(message, 0); continue }
                if (!capacity!!.tryAcquire()) { queueService.changeVisibility(message, 0); continue }
                processingExecutor!!.submit {
                    try { processMessage(message) } finally { capacity!!.release() }
                }
            }
        } catch (exception: RuntimeException) {
            if (running.get()) { log.warn("job_receive_failed reason={}", exception.message); sleep(1_000) }
        }
    }

    internal fun processMessage(message: Message) {
        val queued = try { queueService.parse(message) } catch (exception: RuntimeException) {
            metrics.invalidMessage(); log.error("job_message_invalid messageId={} reason={}", message.messageId(), exception.message)
            queueService.changeVisibility(message, 0); return
        }
        val jobId = queued.jobId
        val leaseToken = UUID.randomUUID()
        val claimed = jobStore.claim(jobId, leaseToken, properties.visibilityTimeout())
        if (claimed.isEmpty) { handleUnclaimed(message, jobId); return }
        val job = claimed.get()
        val execution = ActiveExecution(message, job, leaseToken, Thread.currentThread())
        activeExecutions[job.id] = execution
        val heartbeat = startHeartbeat(execution)
        execution.heartbeat(heartbeat)
        val started = Instant.now()
        metrics.queueLag(job.jobType, Duration.between(job.createdAt, started))
        log.info("job_started jobId={} type={} attempt={} receiveCount={}", job.id, job.jobType, job.attempts, queueService.receiveCount(message))
        try {
            val result = processor.process(job, leaseToken)
            if (execution.leaseLost()) throw JobLeaseLostException(job.id)
            if (!jobStore.markSucceeded(job.id, leaseToken, result)) {
                execution.loseLease(); log.warn("job_completion_ignored_after_lease_loss jobId={} type={}", job.id, job.jobType); return
            }
            metrics.completed(job.jobType, JobStatus.SUCCEEDED, Duration.between(started, Instant.now()))
            queueService.delete(message)
            log.info("job_completed jobId={} type={} attempt={}", job.id, job.jobType, job.attempts)
        } catch (exception: JobLeaseLostException) {
            log.warn("job_processing_stopped_after_lease_loss jobId={} type={}", job.id, job.jobType)
        } catch (exception: RuntimeException) {
            if (execution.leaseLost()) log.warn("job_processing_abandoned_after_lease_loss jobId={} type={}", job.id, job.jobType)
            else handleFailure(message, job, leaseToken, started, exception)
        } finally {
            heartbeat.cancel(false)
            activeExecutions.remove(job.id, execution)
        }
    }

    private fun handleFailure(message: Message, job: BackgroundJob, leaseToken: UUID, started: Instant, exception: RuntimeException) {
        val failure = failureClassifier.classify(exception)
        if (!failure.retryable) {
            if (!jobStore.markFailed(job.id, leaseToken, failure.code, failure.message)) { log.warn("job_failure_ignored_after_lease_loss jobId={}", job.id); return }
            notifyTerminalFailure(job, failure)
            metrics.failed(job.jobType, false)
            metrics.completed(job.jobType, JobStatus.FAILED, Duration.between(started, Instant.now()))
            queueService.delete(message)
            log.warn("job_failed jobId={} type={} retryable=false code={} reason={}", job.id, job.jobType, failure.code, failure.message)
            return
        }
        if (job.attempts >= job.maxAttempts) {
            val code = "RETRIES_EXHAUSTED_${failure.code}"
            val exhausted = JobFailure(code, failure.message, false)
            if (!jobStore.markFailed(job.id, leaseToken, code, failure.message)) { log.warn("job_failure_ignored_after_lease_loss jobId={}", job.id); return }
            notifyTerminalFailure(job, exhausted)
            metrics.failed(job.jobType, true); metrics.retriesExhausted(job.jobType)
            metrics.completed(job.jobType, JobStatus.FAILED, Duration.between(started, Instant.now()))
            deadLetterExhausted(message, job)
            log.error("job_retries_exhausted jobId={} type={} attempts={} reason={}", job.id, job.jobType, job.attempts, failure.message)
            return
        }
        val delay = retryDelayStrategy.delay(job.attempts, properties.retryBaseSeconds)
        if (!jobStore.markRetrying(job.id, leaseToken, failure.code, failure.message, delay)) { log.warn("job_retry_ignored_after_lease_loss jobId={}", job.id); return }
        metrics.retried(job.jobType); queueService.delete(message)
        log.warn("job_retry_scheduled jobId={} type={} attempt={} delaySeconds={} code={} reason={}", job.id, job.jobType, job.attempts, delay.seconds, failure.code, failure.message)
    }

    private fun handleUnclaimed(message: Message, jobId: UUID) {
        val job = jobStore.findById(jobId).orElse(null)
        if (job == null) { queueService.delete(message); return }
        if (job.status.terminal()) {
            if (retryExhausted(job)) deadLetterExhausted(message, job) else queueService.delete(message)
            return
        }
        if (job.status == JobStatus.RETRYING && job.enqueuedAt == null) { queueService.delete(message); return }
        val runAfter = job.runAfter
        val delaySeconds = if (job.status == JobStatus.PROCESSING || runAfter == null) properties.heartbeatSeconds.toLong()
            else maxOf(1, Duration.between(Instant.now(), runAfter).seconds)
        queueService.changeVisibility(message, minOf(delaySeconds, properties.visibilityTimeoutSeconds.toLong()).toInt())
    }
    private fun deadLetterExhausted(message: Message, job: BackgroundJob) {
        try {
            queueService.sendDeadLetter(job.id); queueService.delete(message)
            log.error("job_dead_lettered jobId={} type={} attempts={}", job.id, job.jobType, job.attempts)
        } catch (exception: RuntimeException) {
            log.warn("job_dead_letter_publish_failed jobId={} type={} reason={}", job.id, job.jobType, exception.message)
            try { queueService.changeVisibility(message, minOf(5, properties.visibilityTimeoutSeconds)) }
            catch (visibilityException: RuntimeException) { log.warn("job_dead_letter_retry_visibility_failed jobId={} reason={}", job.id, visibilityException.message) }
        }
    }
    private fun retryExhausted(job: BackgroundJob) = job.errorCode?.startsWith("RETRIES_EXHAUSTED_") == true
    private fun startHeartbeat(execution: ActiveExecution): ScheduledFuture<*> = heartbeatExecutor!!.scheduleAtFixedRate({
        try {
            val extended = jobStore.extendLease(execution.job().id, execution.leaseToken(), properties.visibilityTimeout())
            if (!extended) { execution.loseLeaseAndInterrupt(); log.warn("job_heartbeat_lease_lost jobId={}", execution.job().id); return@scheduleAtFixedRate }
            queueService.changeVisibility(execution.message(), properties.visibilityTimeoutSeconds)
            log.debug("job_heartbeat jobId={}", execution.job().id)
        } catch (exception: RuntimeException) { log.warn("job_heartbeat_failed jobId={} reason={}", execution.job().id, exception.message) }
    }, properties.heartbeatSeconds.toLong(), properties.heartbeatSeconds.toLong(), TimeUnit.SECONDS)
    private fun releaseForShutdown(execution: ActiveExecution) {
        val released = jobStore.releaseForRedispatch(execution.job().id, execution.leaseToken())
        execution.loseLeaseAndInterrupt()
        if (!released) { log.warn("job_shutdown_release_ignored_after_lease_loss jobId={}", execution.job().id); return }
        try { queueService.delete(execution.message()) }
        catch (exception: RuntimeException) { log.warn("job_shutdown_message_delete_failed jobId={} reason={}", execution.job().id, exception.message) }
        log.info("job_released_for_shutdown jobId={} type={}", execution.job().id, execution.job().jobType)
    }
    private fun notifyTerminalFailure(job: BackgroundJob, failure: JobFailure) {
        for (handler in terminalFailureHandlers) {
            if (!handler.supports(job.jobType)) continue
            try { handler.handle(job, failure.code, failure.message) }
            catch (exception: RuntimeException) { log.error("job_terminal_cleanup_failed jobId={} type={} reason={}", job.id, job.jobType, exception.message) }
        }
    }
    private fun daemonThread(runnable: Runnable, name: String) = Thread(runnable, name).apply { isDaemon = true }
    private fun shutdownNow(executor: ExecutorService?, timeoutSeconds: Int) {
        if (executor == null) return
        executor.shutdownNow(); await(executor, timeoutSeconds)
    }
    private fun await(executor: ExecutorService, timeoutSeconds: Int): Boolean = try { executor.awaitTermination(timeoutSeconds.toLong(), TimeUnit.SECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
    private fun sleep(millis: Long) { try { Thread.sleep(millis) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); running.set(false) } }

    private class ActiveExecution(private val message: Message, private val job: BackgroundJob, private val leaseToken: UUID, private val processingThread: Thread) {
        private val leaseLost = AtomicBoolean(false)
        private val heartbeat = AtomicReference<ScheduledFuture<*>?>(null)
        fun message() = message
        fun job() = job
        fun leaseToken() = leaseToken
        fun leaseLost() = leaseLost.get()
        fun loseLease() { leaseLost.set(true) }
        fun loseLeaseAndInterrupt() {
            loseLease(); heartbeat.get()?.cancel(false); processingThread.interrupt()
        }
        fun heartbeat(scheduled: ScheduledFuture<*>) { heartbeat.set(scheduled) }
    }
    companion object { private val log = LoggerFactory.getLogger(JobWorker::class.java) }
}
