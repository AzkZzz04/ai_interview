package dev.jiaming.ai_interview.interview;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.jiaming.ai_interview.coach.AiAnalysisRequest;
import dev.jiaming.ai_interview.coach.AnswerFeedbackRequest;
import dev.jiaming.ai_interview.coach.AnswerFeedbackResponse;
import dev.jiaming.ai_interview.coach.AssessmentResponse;
import dev.jiaming.ai_interview.coach.InterviewQuestionsResponse;

@Service
public class InterviewPersistenceService {

	private final AssessmentPersistenceService assessmentPersistenceService;

	private final InterviewSessionPersistenceService interviewSessionPersistenceService;

	private final AnswerPersistenceService answerPersistenceService;

	public InterviewPersistenceService(
		AssessmentPersistenceService assessmentPersistenceService,
		InterviewSessionPersistenceService interviewSessionPersistenceService,
		AnswerPersistenceService answerPersistenceService
	) {
		this.assessmentPersistenceService = assessmentPersistenceService;
		this.interviewSessionPersistenceService = interviewSessionPersistenceService;
		this.answerPersistenceService = answerPersistenceService;
	}

	@Transactional
	public void saveAssessment(
		UUID assessmentId,
		UUID userId,
		AiAnalysisRequest request,
		String resumeText,
		AssessmentResponse response
	) {
		assessmentPersistenceService.save(assessmentId, userId, request, resumeText, response);
	}

	@Transactional
	public void saveQuestions(
		UUID sessionId,
		UUID userId,
		UUID assessmentId,
		AiAnalysisRequest request,
		InterviewQuestionsResponse response
	) {
		interviewSessionPersistenceService.saveQuestions(sessionId, userId, assessmentId, request, response);
	}

	@Transactional
	public void saveAnswer(
		UUID answerId,
		UUID userId,
		AnswerFeedbackRequest request,
		String resumeText,
		AnswerFeedbackResponse response
	) {
		UUID questionId = interviewSessionPersistenceService.findLatestQuestion(userId, request.questionText())
			.orElseGet(() -> interviewSessionPersistenceService.createQuestionForAnswer(userId, request, resumeText));
		answerPersistenceService.save(answerId, questionId, request, response);
	}
}
