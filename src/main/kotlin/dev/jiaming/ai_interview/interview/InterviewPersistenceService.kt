package dev.jiaming.ai_interview.interview

import dev.jiaming.ai_interview.coach.AnswerFeedbackResponse
import dev.jiaming.ai_interview.coach.AssessmentResponse
import dev.jiaming.ai_interview.coach.InterviewQuestionsResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class InterviewPersistenceService(
    private val assessmentPersistenceService: AssessmentPersistenceService,
    private val interviewSessionPersistenceService: InterviewSessionPersistenceService,
    private val answerPersistenceService: AnswerPersistenceService,
) {
    @Transactional
    fun saveAssessment(assessmentId: UUID, input: AnalysisPersistenceInput, response: AssessmentResponse) {
        assessmentPersistenceService.save(assessmentId, input, response)
    }

    @Transactional
    fun saveQuestions(
        sessionId: UUID,
        assessmentId: UUID,
        input: AnalysisPersistenceInput,
        response: InterviewQuestionsResponse,
    ) {
        interviewSessionPersistenceService.saveQuestions(sessionId, assessmentId, input, response)
    }

    @Transactional
    fun saveAnswer(answerId: UUID, input: FeedbackPersistenceInput, response: AnswerFeedbackResponse) {
        val questionId = interviewSessionPersistenceService.findLatestQuestion(input)
            .orElseGet { interviewSessionPersistenceService.createQuestionForAnswer(input) }
        answerPersistenceService.save(answerId, questionId, input, response)
    }
}
