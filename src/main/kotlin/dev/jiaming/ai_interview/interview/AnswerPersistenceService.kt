package dev.jiaming.ai_interview.interview

import dev.jiaming.ai_interview.coach.AnswerFeedbackResponse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AnswerPersistenceService(
    private val jdbcTemplate: JdbcTemplate,
    private val jsonSupport: PersistenceJsonSupport,
) {
    fun save(answerId: UUID, questionId: UUID, input: FeedbackPersistenceInput, response: AnswerFeedbackResponse) {
        jdbcTemplate.update(
            """
                INSERT INTO ai_interview_app.interview_answers (
                    id, question_id, answer_text, score, feedback, model_name, prompt_name
                )
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
            """.trimIndent(),
            answerId,
            questionId,
            input.answerText,
            response.score,
            jsonSupport.json(mapOf(
                "summary" to response.summary,
                "nextStep" to response.nextStep,
                "strengths" to response.strengths,
                "gaps" to response.gaps,
                "betterAnswerOutline" to response.betterAnswerOutline,
                "followUpQuestion" to response.followUpQuestion,
                "sourceContextIds" to response.sourceContextIds,
            )),
            jsonSupport.model(response.modelProvider),
            jsonSupport.promptName(),
        )
    }
}
