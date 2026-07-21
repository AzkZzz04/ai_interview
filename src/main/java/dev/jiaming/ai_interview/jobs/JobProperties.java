package dev.jiaming.ai_interview.jobs;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.jobs")
public record JobProperties(
	boolean enabled,
	String endpoint,
	String region,
	String accessKey,
	String secretKey,
	String queueName,
	String dlqName,
	int maxReceiveCount,
	int workerConcurrency,
	int longPollSeconds,
	int visibilityTimeoutSeconds,
	int heartbeatSeconds,
	int maxAttempts,
	int retryBaseSeconds,
	int reuseWindowSeconds,
	long dispatchIntervalMs,
	long leaseReaperIntervalMs,
	long cleanupIntervalMs,
	int shutdownGraceSeconds,
	int retentionDays
) {

	public JobProperties {
		region = nonBlank(region, "us-east-1");
		queueName = nonBlank(queueName, "ai-interview-jobs");
		dlqName = nonBlank(dlqName, queueName + "-dlq");
		maxReceiveCount = maxReceiveCount <= 0 ? 3 : maxReceiveCount;
		workerConcurrency = workerConcurrency <= 0 ? 2 : workerConcurrency;
		longPollSeconds = Math.max(0, Math.min(20, longPollSeconds <= 0 ? 20 : longPollSeconds));
		visibilityTimeoutSeconds = visibilityTimeoutSeconds < 30 ? 300 : visibilityTimeoutSeconds;
		int requestedHeartbeat = heartbeatSeconds <= 0 ? 60 : heartbeatSeconds;
		heartbeatSeconds = Math.max(1, Math.min(requestedHeartbeat, visibilityTimeoutSeconds / 2));
		maxAttempts = maxAttempts <= 0 ? 3 : maxAttempts;
		retryBaseSeconds = retryBaseSeconds <= 0 ? 15 : retryBaseSeconds;
		reuseWindowSeconds = reuseWindowSeconds <= 0 ? 300 : reuseWindowSeconds;
		dispatchIntervalMs = dispatchIntervalMs <= 0 ? 5_000 : dispatchIntervalMs;
		leaseReaperIntervalMs = leaseReaperIntervalMs <= 0 ? 30_000 : leaseReaperIntervalMs;
		cleanupIntervalMs = cleanupIntervalMs <= 0 ? 3_600_000 : cleanupIntervalMs;
		shutdownGraceSeconds = shutdownGraceSeconds <= 0 ? 120 : shutdownGraceSeconds;
		retentionDays = retentionDays <= 0 ? 7 : retentionDays;
	}

	public Duration visibilityTimeout() {
		return Duration.ofSeconds(visibilityTimeoutSeconds);
	}

	public Duration reuseWindow() {
		return Duration.ofSeconds(reuseWindowSeconds);
	}

	private static String nonBlank(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value.trim();
	}
}
