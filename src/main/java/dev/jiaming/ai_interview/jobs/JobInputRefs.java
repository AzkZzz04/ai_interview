package dev.jiaming.ai_interview.jobs;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public record JobInputRefs(
	UUID resumeId,
	UUID jobDescriptionId
) {
	public static JobInputRefs from(BackgroundJob job) {
		JsonNode payload = job.requestPayload();
		UUID resumeId = uuid(payload, "resumeId");
		UUID jobDescriptionId = uuid(payload, "jobDescriptionId");
		if (resumeId == null) {
			resumeId = job.resourceId();
		}
		return new JobInputRefs(resumeId, jobDescriptionId);
	}

	private static UUID uuid(JsonNode payload, String field) {
		if (payload == null || !payload.hasNonNull(field) || payload.get(field).asText().isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(payload.get(field).asText());
		}
		catch (IllegalArgumentException exception) {
			return null;
		}
	}
}
