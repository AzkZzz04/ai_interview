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

	public AiAnalysisRequest asRequest() {
		return new AiAnalysisRequest(
			resume.resourceId(),
			resume.normalizedText(),
			jobDescription.map(ResolvedDocument::resourceId).orElse(null),
			jobDescription.map(ResolvedDocument::normalizedText).orElse(""),
			targetRole,
			seniority
		);
	}
}
