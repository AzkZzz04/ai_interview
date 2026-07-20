package dev.jiaming.ai_interview.jobs;

import dev.jiaming.ai_interview.coach.AssessmentResponse;
import dev.jiaming.ai_interview.coach.InterviewQuestionsResponse;

public record AnalysisJobResult(
	AssessmentResponse assessment,
	InterviewQuestionsResponse questions
) {
}
