package dev.jiaming.ai_interview.coach;

import java.util.Optional;

import dev.jiaming.ai_interview.document.ResolvedDocument;

public record CoachAnalysisInput(
	ResolvedDocument resume,
	Optional<ResolvedDocument> jobDescription,
	String targetRole,
	String seniority
) {
	public CoachAnalysisInput {
		jobDescription = jobDescription == null ? Optional.empty() : jobDescription;
	}

}
