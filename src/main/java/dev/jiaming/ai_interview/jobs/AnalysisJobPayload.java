package dev.jiaming.ai_interview.jobs;

import java.util.UUID;

public record AnalysisJobPayload(
	int payloadVersion,
	UUID resumeId,
	UUID jobDescriptionId,
	String targetRole,
	String seniority
) {
	public static final int CURRENT_VERSION = 2;

	public AnalysisJobPayload(UUID resumeId, UUID jobDescriptionId, String targetRole, String seniority) {
		this(CURRENT_VERSION, resumeId, jobDescriptionId, targetRole, seniority);
	}
}
