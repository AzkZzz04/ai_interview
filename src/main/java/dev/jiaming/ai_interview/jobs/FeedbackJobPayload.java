package dev.jiaming.ai_interview.jobs;

import java.util.List;
import java.util.UUID;

public record FeedbackJobPayload(
	int payloadVersion,
	UUID resumeId,
	UUID jobDescriptionId,
	String targetRole,
	String seniority,
	String questionText,
	String category,
	List<String> expectedSignals,
	String answerText
) {
	public static final int CURRENT_VERSION = 2;

	public FeedbackJobPayload(
		UUID resumeId,
		UUID jobDescriptionId,
		String targetRole,
		String seniority,
		String questionText,
		String category,
		List<String> expectedSignals,
		String answerText
	) {
		this(
			CURRENT_VERSION,
			resumeId,
			jobDescriptionId,
			targetRole,
			seniority,
			questionText,
			category,
			expectedSignals == null ? List.of() : List.copyOf(expectedSignals),
			answerText
		);
	}

	public FeedbackJobPayload {
		expectedSignals = expectedSignals == null ? List.of() : List.copyOf(expectedSignals);
	}
}
