package dev.jiaming.ai_interview.coach;

import java.util.UUID;

public record AiAnalysisRequest(
	UUID resumeId,
	String resumeText,
	UUID jobDescriptionId,
	String jobDescription,
	String targetRole,
	String seniority
) {
	public AiAnalysisRequest(String resumeText, String jobDescription, String targetRole, String seniority) {
		this(null, resumeText, null, jobDescription, targetRole, seniority);
	}
}
