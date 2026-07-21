package dev.jiaming.ai_interview.interview;

import java.util.UUID;

import dev.jiaming.ai_interview.coach.CoachAnalysisInput;
import dev.jiaming.ai_interview.document.ResolvedDocument;

public record AnalysisPersistenceInput(
	UUID userId,
	UUID resumeId,
	String resumeHash,
	UUID jobDescriptionId,
	String jobDescriptionHash,
	String targetRole,
	String seniority
) {

	public static AnalysisPersistenceInput from(UUID userId, CoachAnalysisInput input) {
		return new AnalysisPersistenceInput(
			userId,
			input.resume().resourceId(),
			input.resume().contentHash(),
			input.jobDescription().map(ResolvedDocument::resourceId).orElse(null),
			input.jobDescription().map(ResolvedDocument::contentHash).orElse(""),
			input.targetRole(),
			input.seniority()
		);
	}
}
