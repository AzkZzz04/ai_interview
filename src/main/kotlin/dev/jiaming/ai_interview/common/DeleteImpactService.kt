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
            "SELECT count(*) FROM ai_interview_app.resume_assessments WHERE resume_id = ? AND user_id = ?",
            Int::class.java, resumeId, userId
        ) ?: 0
        return DeleteImpact(scores, 0, 0, 0, 0, 0)
    }
}
