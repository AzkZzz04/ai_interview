package dev.jiaming.ai_interview.jobs

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.jobs")
data class JobProperties(
    val enabled: Boolean, val endpoint: String?, var region: String?, val accessKey: String?, val secretKey: String?,
    var queueName: String?, var dlqName: String?, var maxReceiveCount: Int, var workerConcurrency: Int,
    var longPollSeconds: Int, var visibilityTimeoutSeconds: Int, var heartbeatSeconds: Int, var maxAttempts: Int,
    var retryBaseSeconds: Int, var dispatchIntervalMs: Long,
    var leaseReaperIntervalMs: Long, var cleanupIntervalMs: Long, var shutdownGraceSeconds: Int, var retentionDays: Int
) {
    init {
        region = nonBlank(region, "us-east-1")
        queueName = nonBlank(queueName, "ai-interview-jobs")
        dlqName = nonBlank(dlqName, queueName + "-dlq")
        maxReceiveCount = if (maxReceiveCount <= 0) 3 else maxReceiveCount
        workerConcurrency = if (workerConcurrency <= 0) 2 else workerConcurrency
        longPollSeconds = longPollSeconds.coerceIn(0, 20).let { if (it <= 0) 20 else it }
        visibilityTimeoutSeconds = if (visibilityTimeoutSeconds < 30) 300 else visibilityTimeoutSeconds
        val requestedHeartbeat = if (heartbeatSeconds <= 0) 60 else heartbeatSeconds
        heartbeatSeconds = requestedHeartbeat.coerceIn(1, visibilityTimeoutSeconds / 2)
        maxAttempts = if (maxAttempts <= 0) 3 else maxAttempts
        retryBaseSeconds = if (retryBaseSeconds <= 0) 15 else retryBaseSeconds
        dispatchIntervalMs = if (dispatchIntervalMs <= 0) 5_000 else dispatchIntervalMs
        leaseReaperIntervalMs = if (leaseReaperIntervalMs <= 0) 30_000 else leaseReaperIntervalMs
        cleanupIntervalMs = if (cleanupIntervalMs <= 0) 3_600_000 else cleanupIntervalMs
        shutdownGraceSeconds = if (shutdownGraceSeconds <= 0) 120 else shutdownGraceSeconds
        retentionDays = if (retentionDays <= 0) 7 else retentionDays
    }
    fun region(): String = region!!
    fun queueName(): String = queueName!!
    fun dlqName(): String = dlqName!!
    fun visibilityTimeout() = Duration.ofSeconds(visibilityTimeoutSeconds.toLong())
    private fun nonBlank(value: String?, fallback: String) = value?.takeIf { it.isNotBlank() }?.trim() ?: fallback
}
