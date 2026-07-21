package dev.jiaming.ai_interview.coach;

import java.util.List;
import java.util.Optional;

import dev.jiaming.ai_interview.document.ResolvedDocument;

public record CoachFeedbackInput(
	ResolvedDocument resume,
	Optional<ResolvedDocument> jobDescription,
	String targetRole,
	String seniority,
	String questionText,
	String category,
	List<String> expectedSignals,
	String answerText
) {
	public CoachFeedbackInput {
		jobDescription = jobDescription == null ? Optional.empty() : jobDescription;
		expectedSignals = expectedSignals == null ? List.of() : List.copyOf(expectedSignals);
	}

	public AnswerFeedbackRequest asRequest() {
		return new AnswerFeedbackRequest(
			resume.resourceId(),
			resume.normalizedText(),
			jobDescription.map(ResolvedDocument::resourceId).orElse(null),
			jobDescription.map(ResolvedDocument::normalizedText).orElse(""),
			targetRole,
			seniority,
			questionText,
			category,
			expectedSignals,
			answerText
		);
	}
}
