package dev.jiaming.ai_interview.history

import dev.jiaming.ai_interview.common.LocalUserService
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@JvmRecord data class HistoryResponse(val resumes: List<ResumeHistory>, val targetJobs: List<TargetJobHistory>, val practiceSets: List<PracticeSetHistory>)
@JvmRecord data class ResumeHistory(val id: UUID, val name: String, val scores: List<ScorePoint>)
@JvmRecord data class ScorePoint(val overall: Int, val scoredAt: Instant)
@JvmRecord data class TargetJobHistory(val id: UUID, val name: String, val fits: List<FitPoint>)
@JvmRecord data class FitPoint(val resumeId: UUID, val resumeName: String, val fitScore: Int, val createdAt: Instant)
@JvmRecord data class PracticeSetHistory(
    val id: UUID, val resumeId: UUID, val resumeName: String, val targetJobId: UUID, val targetJobName: String,
    val updatedAt: Instant, val questions: List<QuestionHistory>
)
@JvmRecord data class QuestionHistory(val id: UUID, val text: String, val scores: List<Int>)

/** Trends from saved results only, owner-scoped and oldest point first; history never calls the AI. */
@Service
class HistoryService(private val jdbcTemplate: JdbcTemplate, private val localUserService: LocalUserService) {
    fun history(): HistoryResponse {
        val userId = localUserService.localUserId()
        return HistoryResponse(resumes(userId), targetJobs(userId), practiceSets(userId))
    }

    private fun resumes(userId: UUID) = grouped(
        """
            SELECT r.id, r.name, s.overall, s.scored_at
            FROM ai_interview_app.resumes r
            LEFT JOIN ai_interview_app.resume_scores s ON s.resume_id = r.id AND s.user_id = r.user_id
            WHERE r.user_id = ?
            ORDER BY r.created_at DESC, r.id DESC, s.scored_at, s.id
        """, userId,
        key = { it.uuid("id") },
        parent = { ResumeHistory(it.uuid("id"), it.getString("name"), mutableListOf()) },
        child = { rs, resume -> rs.getTimestamp("scored_at")?.let { (resume.scores as MutableList) += ScorePoint(rs.getInt("overall"), it.toInstant()) } }
    )

    private fun targetJobs(userId: UUID) = grouped(
        """
            SELECT t.id, t.name, f.resume_id, r.name AS resume_name,
                   (f.result_payload ->> 'fitScore')::int AS fit_score, f.result_created_at
            FROM ai_interview_app.job_descriptions t
            LEFT JOIN ai_interview_app.job_fits f
              ON f.target_job_id = t.id AND f.user_id = t.user_id AND f.result_payload IS NOT NULL
            LEFT JOIN ai_interview_app.resumes r ON r.id = f.resume_id AND r.user_id = f.user_id
            WHERE t.user_id = ?
            ORDER BY t.created_at DESC, t.id DESC, f.result_created_at, f.id
        """, userId,
        key = { it.uuid("id") },
        parent = { TargetJobHistory(it.uuid("id"), it.getString("name"), mutableListOf()) },
        child = { rs, job ->
            rs.getObject("resume_id", UUID::class.java)?.let {
                (job.fits as MutableList) += FitPoint(it, rs.getString("resume_name"), rs.getInt("fit_score"), rs.getTimestamp("result_created_at").toInstant())
            }
        }
    )

    private fun practiceSets(userId: UUID): List<PracticeSetHistory> {
        val questions = LinkedHashMap<UUID, QuestionHistory>()
        return grouped(
            """
                SELECT ps.id, ps.resume_id, r.name AS resume_name, ps.target_job_id, t.name AS target_job_name, ps.updated_at,
                       q.id AS question_id, q.text, a.score
                FROM ai_interview_app.practice_sets ps
                JOIN ai_interview_app.resumes r ON r.id = ps.resume_id AND r.user_id = ps.user_id
                JOIN ai_interview_app.job_descriptions t ON t.id = ps.target_job_id AND t.user_id = ps.user_id
                LEFT JOIN ai_interview_app.practice_questions q ON q.practice_set_id = ps.id AND q.user_id = ps.user_id
                LEFT JOIN ai_interview_app.answer_attempts a ON a.question_id = q.id AND a.user_id = ps.user_id AND a.score IS NOT NULL
                WHERE ps.user_id = ?
                ORDER BY ps.updated_at DESC, ps.id, q.origin = 'USER', q.order_index, a.number
            """, userId,
            key = { it.uuid("id") },
            parent = {
                PracticeSetHistory(it.uuid("id"), it.uuid("resume_id"), it.getString("resume_name"), it.uuid("target_job_id"),
                    it.getString("target_job_name"), it.getTimestamp("updated_at").toInstant(), mutableListOf())
            },
            child = { rs, set ->
                rs.getObject("question_id", UUID::class.java)?.let { questionId ->
                    val question = questions.getOrPut(questionId) {
                        QuestionHistory(questionId, rs.getString("text"), mutableListOf()).also { (set.questions as MutableList) += it }
                    }
                    rs.getObject("score")?.let { (question.scores as MutableList) += rs.getInt("score") }
                }
            }
        )
    }

    // One row per child (or one null-child row), already ordered; folds rows into parents in first-seen order.
    private fun <P> grouped(sql: String, userId: UUID, key: (ResultSet) -> UUID, parent: (ResultSet) -> P, child: (ResultSet, P) -> Unit): List<P> {
        val parents = LinkedHashMap<UUID, P>()
        jdbcTemplate.query(sql.trimIndent(), { rs -> child(rs, parents.getOrPut(key(rs)) { parent(rs) }) }, userId)
        return parents.values.toList()
    }

    private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)
}
