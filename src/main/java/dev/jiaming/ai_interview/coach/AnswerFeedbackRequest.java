package dev.jiaming.ai_interview.coach;

import java.util.List;
import java.util.UUID;

public record AnswerFeedbackRequest(
	UUID resumeId,
	String resumeText,
	UUID jobDescriptionId,
	String jobDescription,
	String targetRole,
	String seniority,
	String questionText,
	String category,
	List<String> expectedSignals,
	String answerText
) {
	public AnswerFeedbackRequest(
		String resumeText,
		String jobDescription,
		String targetRole,
		String seniority,
		String questionText,
		String category,
		List<String> expectedSignals,
		String answerText
	) {
		this(
			null,
			resumeText,
			null,
			jobDescription,
			targetRole,
			seniority,
			questionText,
			category,
			expectedSignals,
			answerText
		);
	}
}
