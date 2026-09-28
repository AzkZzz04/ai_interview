package dev.jiaming.ai_interview.interview

import dev.jiaming.ai_interview.coach.InterviewQuestionResponse
import dev.jiaming.ai_interview.coach.InterviewQuestionsResponse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.Optional
import java.util.UUID

@Service
class InterviewSessionPersistenceService(
    private val jdbcTemplate: JdbcTemplate,
    private val jsonSupport: PersistenceJsonSupport,
) {
    fun saveQuestions(
        sessionId: UUID,
        assessmentId: UUID,
        input: AnalysisPersistenceInput,
        response: InterviewQuestionsResponse,
    ) {
        createSession(
            sessionId,
            input.userId,
            input.resumeId,
            input.jobDescriptionId,
            assessmentId,
            input.targetRole,
            input.seniority,
        )
        response.questions!!.forEachIndexed { index, question -> saveQuestion(sessionId, question, index) }
    }

    fun findLatestQuestion(input: FeedbackPersistenceInput): Optional<UUID> {
        val questionText = input.questionText
        if (questionText == null || questionText.isBlank()) return Optional.empty()
        return jdbcTemplate.query(
            """
                SELECT q.id
                FROM ai_interview_app.interview_questions q
                JOIN ai_interview_app.interview_sessions s ON s.id = q.session_id
                WHERE s.user_id = ?
                  AND s.resume_id = ?
                  AND s.job_description_id IS NOT DISTINCT FROM ?
                  AND s.target_role IS NOT DISTINCT FROM ?
                  AND s.seniority IS NOT DISTINCT FROM ?
                  AND q.question_text = ?
                  AND q.category = ?
                ORDER BY q.created_at DESC
                LIMIT 1
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            input.userId,
            input.resumeId,
            input.jobDescriptionId,
            input.targetRole,
            input.seniority,
            questionText,
            category(input.category),
        ).stream().findFirst()
    }

    fun createQuestionForAnswer(input: FeedbackPersistenceInput): UUID {
        val sessionId = UUID.randomUUID()
        createSession(
            sessionId,
            input.userId,
            input.resumeId,
            input.jobDescriptionId,
            null,
            input.targetRole,
            input.seniority,
        )
        val questionId = UUID.randomUUID()
        jdbcTemplate.update(
            """
                INSERT INTO ai_interview_app.interview_questions (
                    id, session_id, question_text, category, difficulty,
                    expected_signals, source_context, order_index
                )
                VALUES (?, ?, ?, ?, ?, ?::jsonb, '[]'::jsonb, 0)
            """.trimIndent(),
            questionId,
            sessionId,
            input.questionText,
            category(input.category),
            "Core",
            jsonSupport.json(input.expectedSignals),
        )
        return questionId
    }

    private fun category(value: String?): String = if (value.isNullOrBlank()) "Interview" else value

    private fun createSession(
        sessionId: UUID,
        userId: UUID?,
        resumeId: UUID?,
        jobDescriptionId: UUID?,
        assessmentId: UUID?,
        targetRole: String?,
        seniority: String?,
    ) {
        jdbcTemplate.update(
            """
                INSERT INTO ai_interview_app.interview_sessions (
                    id, user_id, resume_id, job_description_id, assessment_id, target_role, seniority, status
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, 'READY')
            """.trimIndent(),
            sessionId,
            userId,
            resumeId,
            jobDescriptionId,
            assessmentId,
            targetRole,
            seniority,
        )
    }

    private fun saveQuestion(sessionId: UUID, question: InterviewQuestionResponse, orderIndex: Int) {
        jdbcTemplate.update(
            """
                INSERT INTO ai_interview_app.interview_questions (
                    id, session_id, question_text, category, difficulty,
                    expected_signals, source_context, order_index
                )
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            sessionId,
            question.questionText,
            question.category,
            question.difficulty,
            jsonSupport.json(question.expectedSignals),
            jsonSupport.json(question.sourceContextIds),
            orderIndex,
        )
    }
}
