package dev.jiaming.ai_interview.jobs;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public record JobStatusResponse(
	UUID jobId,
	JobType jobType,
	JobStatus status,
	JobStage stage,
	int attempts,
	Object result,
	JobErrorResponse error,
	Instant createdAt,
	Instant startedAt,
	Instant completedAt
) {

	public static JobStatusResponse from(BackgroundJob job) {
		JobErrorResponse error = job.lastError() == null
			? null
			: new JobErrorResponse(job.errorCode(), job.lastError(), job.retryable());
		return new JobStatusResponse(
			job.id(),
			job.jobType(),
			job.status(),
			job.stage(),
			job.attempts(),
			jsonValue(job.resultPayload()),
			error,
			job.createdAt(),
			job.startedAt(),
			job.completedAt()
		);
	}

	private static Object jsonValue(JsonNode node) {
		if (node == null || node.isNull() || node.isMissingNode()) {
			return null;
		}
		if (node.isObject()) {
			Map<String, Object> value = new LinkedHashMap<>();
			node.properties().forEach(entry -> value.put(entry.getKey(), jsonValue(entry.getValue())));
			return value;
		}
		if (node.isArray()) {
			List<Object> value = new ArrayList<>();
			node.forEach(child -> value.add(jsonValue(child)));
			return value;
		}
		if (node.isNumber()) {
			return node.numberValue();
		}
		if (node.isBoolean()) {
			return node.booleanValue();
		}
		return node.asText();
	}
}
