package dev.jiaming.ai_interview.jobs;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public record BackgroundJob(
	UUID id,
	UUID userId,
	JobType jobType,
	String resourceType,
	UUID resourceId,
	JobStatus status,
	JobStage stage,
	JsonNode requestPayload,
	JsonNode resultPayload,
	String requestFingerprint,
	int attempts,
	int maxAttempts,
	String errorCode,
	String lastError,
	Boolean retryable,
	Instant runAfter,
	Instant createdAt,
	Instant updatedAt,
	Instant enqueuedAt,
	Instant startedAt,
	Instant completedAt,
	UUID leaseToken,
	Instant leaseExpiresAt
) {
}
