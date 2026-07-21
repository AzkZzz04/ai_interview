package dev.jiaming.ai_interview.interview;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
		AnalysisPersistenceInput input,
		AssessmentResponse response
	) {
		assessmentPersistenceService.save(assessmentId, input, response);
	}

	@Transactional
	public void saveQuestions(
		UUID sessionId,
		UUID assessmentId,
		AnalysisPersistenceInput input,
		InterviewQuestionsResponse response
	) {
		interviewSessionPersistenceService.saveQuestions(sessionId, assessmentId, input, response);
	}

	@Transactional
	public void saveAnswer(
		UUID answerId,
		FeedbackPersistenceInput input,
		AnswerFeedbackResponse response
	) {
		UUID questionId = interviewSessionPersistenceService.findLatestQuestion(input)
			.orElseGet(() -> interviewSessionPersistenceService.createQuestionForAnswer(input));
		answerPersistenceService.save(answerId, questionId, input, response);
	}
}
