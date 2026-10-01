package dev.jiaming.ai_interview.score

import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.jobs.JobAcceptedResponse
import dev.jiaming.ai_interview.jobs.JobSubmissionService
import dev.jiaming.ai_interview.jobs.JobType
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ResumeScoreService(
    private val jdbcTemplate: JdbcTemplate,
    private val localUserService: LocalUserService,
    private val jobSubmissionService: JobSubmissionService
) {
    /** Snapshots the ready resume's text and job title into a new score job; the row lock keeps a delete from racing it. */
    @Transactional
    fun submit(resumeId: UUID): JobAcceptedResponse {
        val userId = localUserService.localUserId()
        // Owner before resume, the order resume deletion locks them in, so the two cannot deadlock.
        jdbcTemplate.queryForList("SELECT id FROM ai_interview_app.app_users WHERE id = ? FOR KEY SHARE", userId)
        val resume = jdbcTemplate.query(
            "SELECT processing_status, job_title, normalized_text FROM ai_interview_app.resumes WHERE id = ? AND user_id = ? FOR SHARE",
            RowMapper { rs, _ -> ScoreSource(rs.getString("processing_status"), rs.getString("job_title"), rs.getString("normalized_text")) },
            resumeId, userId
        ).firstOrNull() ?: throw ApiRequestException(HttpStatus.NOT_FOUND, "RESUME_NOT_FOUND", "Resume was not found")
        if (resume.status != "READY" || resume.text.isNullOrBlank()) {
            throw ApiRequestException(HttpStatus.CONFLICT, "RESUME_NOT_READY", "Resume text is not ready to score")
        }
        return jobSubmissionService.submit(JobType.RESUME_SCORE, "resume", resumeId, ResumeScorePayload(resumeId, resume.text, resume.jobTitle))
    }

    private data class ScoreSource(val status: String, val jobTitle: String?, val text: String?)
}
