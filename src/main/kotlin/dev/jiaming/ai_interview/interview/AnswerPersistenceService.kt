package dev.jiaming.ai_interview.interview

import dev.jiaming.ai_interview.coach.AnswerFeedbackResponse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.Map
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
            jsonSupport.json(Map.of<String, Any?>(
                "summary", response.summary,
                "nextStep", response.nextStep,
                "strengths", response.strengths,
                "gaps", response.gaps,
                "betterAnswerOutline", response.betterAnswerOutline,
                "followUpQuestion", response.followUpQuestion,
                "sourceContextIds", response.sourceContextIds,
            )),
            jsonSupport.model(response.modelProvider),
            jsonSupport.promptName(),
        )
    }
}
