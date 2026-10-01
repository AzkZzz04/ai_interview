package dev.jiaming.ai_interview.common

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@JvmRecord
data class DeleteImpact(
    val scores: Int,
    val fits: Int,
    val suggestionSets: Int,
    val practiceSets: Int,
    val attempts: Int,
    val staleSuggestionSets: Int
)

@Service
class DeleteImpactService(private val jdbcTemplate: JdbcTemplate) {
    fun forResume(userId: java.util.UUID, resumeId: java.util.UUID): DeleteImpact {
        val exists = jdbcTemplate.query(
            "SELECT id FROM ai_interview_app.resumes WHERE id = ? AND user_id = ?",
            { rs, _ -> rs.getObject("id", java.util.UUID::class.java) }, resumeId, userId
        ).isNotEmpty()
        if (!exists) throw ApiRequestException(HttpStatus.NOT_FOUND, "RESUME_NOT_FOUND", "Resume was not found")

        val scores = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ai_interview_app.resume_scores WHERE resume_id = ? AND user_id = ?",
            Int::class.java, resumeId, userId
        ) ?: 0
        val fits = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ai_interview_app.job_fits WHERE resume_id = ? AND user_id = ? AND result_payload IS NOT NULL",
            Int::class.java, resumeId, userId
        ) ?: 0
        val practiceSets = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ai_interview_app.practice_sets WHERE resume_id = ? AND user_id = ?",
            Int::class.java, resumeId, userId
        ) ?: 0
        return DeleteImpact(scores, fits, 0, practiceSets, countPracticeAttempts(jdbcTemplate, userId, "resume_id", resumeId), 0)
    }
}

/** Attempts in the owner's practice sets whose [pairColumn] (`resume_id` or `target_job_id`) is [id]; deleting that side cascades to exactly these. */
internal fun countPracticeAttempts(jdbcTemplate: JdbcTemplate, userId: java.util.UUID, pairColumn: String, id: java.util.UUID): Int {
    require(pairColumn == "resume_id" || pairColumn == "target_job_id") { "Unsupported pair column $pairColumn" }
    return jdbcTemplate.queryForObject(
        """
            SELECT count(*)
            FROM ai_interview_app.answer_attempts a
            JOIN ai_interview_app.practice_questions q ON q.id = a.question_id AND q.user_id = a.user_id
            JOIN ai_interview_app.practice_sets s ON s.id = q.practice_set_id AND s.user_id = q.user_id
            WHERE s.$pairColumn = ? AND s.user_id = ?
        """.trimIndent(),
        Int::class.java, id, userId
    ) ?: 0
}
