package dev.jiaming.ai_interview.interview;

import java.util.List;
import java.util.UUID;

import dev.jiaming.ai_interview.coach.CoachFeedbackInput;
import dev.jiaming.ai_interview.document.ResolvedDocument;

public record FeedbackPersistenceInput(
	UUID userId,
	UUID resumeId,
	String resumeHash,
	UUID jobDescriptionId,
	String jobDescriptionHash,
	String targetRole,
	String seniority,
	String questionText,
	String category,
	List<String> expectedSignals,
	String answerText
) {

	public FeedbackPersistenceInput {
		expectedSignals = expectedSignals == null ? List.of() : List.copyOf(expectedSignals);
	}

	public static FeedbackPersistenceInput from(UUID userId, CoachFeedbackInput input) {
		return new FeedbackPersistenceInput(
			userId,
			input.resume().resourceId(),
			input.resume().contentHash(),
			input.jobDescription().map(ResolvedDocument::resourceId).orElse(null),
			input.jobDescription().map(ResolvedDocument::contentHash).orElse(""),
			input.targetRole(),
			input.seniority(),
			input.questionText(),
			input.category(),
			input.expectedSignals(),
			input.answerText()
		);
	}
}
