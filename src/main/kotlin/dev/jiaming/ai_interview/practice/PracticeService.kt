package dev.jiaming.ai_interview.practice

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.jobs.ActiveJob
import dev.jiaming.ai_interview.jobs.BackgroundJobStore
import dev.jiaming.ai_interview.jobs.JobStatus
import dev.jiaming.ai_interview.jobs.JobSubmissionService
import dev.jiaming.ai_interview.jobs.JobType
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations

@Service
class PracticeService(
    private val jdbcTemplate: JdbcTemplate,
    private val localUserService: LocalUserService,
    private val jobSubmissionService: JobSubmissionService,
    private val requestGuard: RedisRequestGuard,
    private val backgroundJobStore: BackgroundJobStore,
    private val objectMapper: ObjectMapper,
    private val transactionOperations: TransactionOperations,
) {
    fun get(setId: UUID): PracticeSetView {
        val userId = localUserService.localUserId()
        return view(userId, findSet(userId, "id = ?", setId) ?: notFound())
    }

    /** Returns the pair's set, creating it with its first generation job when it does not exist yet (§7.1). */
    fun create(resumeId: UUID, targetJobId: UUID): PracticeSetCreation {
        jobSubmissionService.assertApiAvailable()
        val userId = localUserService.localUserId()
        return inTransaction {
            lockOwner(userId)
            val resumeReady = lockInputs(userId, resumeId, targetJobId)
            val existing = findPairSet(userId, resumeId, targetJobId)
            when {
                existing != null -> PracticeSetCreation(view(userId, existing), false)
                !resumeReady -> throw ApiRequestException(HttpStatus.CONFLICT, "RESUME_NOT_READY", "Resume is not ready for practice")
                else -> {
                    // The unique pair constraint picks one winner among concurrent creates; the loser returns the winner's set.
                    val setId = jdbcTemplate.query(
                        """
                            INSERT INTO ai_interview_app.practice_sets (user_id, resume_id, target_job_id)
                            VALUES (?, ?, ?)
                            ON CONFLICT (user_id, resume_id, target_job_id) DO NOTHING
                            RETURNING id
                        """.trimIndent(),
                        RowMapper { rs, _ -> rs.getObject("id", UUID::class.java) }, userId, resumeId, targetJobId,
                    ).firstOrNull()
                    if (setId == null) {
                        PracticeSetCreation(view(userId, findPairSet(userId, resumeId, targetJobId) ?: notFound()), false)
                    } else {
                        val set = findSet(userId, "id = ?", setId) ?: notFound()
                        startGeneration(set)
                        PracticeSetCreation(view(userId, set), true)
                    }
                }
            }
        }
    }

    /** Starts a new generation job for a set whose last generation failed (§7.3). */
    fun retry(setId: UUID): PracticeSetView {
        jobSubmissionService.assertApiAvailable()
        val userId = localUserService.localUserId()
        return inTransaction {
            lockOwner(userId)
            val found = findSet(userId, "id = ?", setId) ?: notFound()
            // Lock the pair's rows before the set, in create's order, so a concurrent delete either removes this job or wins first.
            lockInputs(userId, found.resumeId, found.targetJobId)
            val set = findSet(userId, "id = ? FOR UPDATE", setId) ?: notFound()
            if (view(userId, set).status != PracticeSetStatus.FAILED) {
                throw ApiRequestException(HttpStatus.CONFLICT, "PRACTICE_SET_NOT_FAILED", "Practice set generation did not fail")
            }
            startGeneration(set)
            view(userId, set)
        }
    }

    /** Appends a user question after the AI questions (§7.4). [text] is already trimmed and checked. */
    fun addQuestion(setId: UUID, text: String): PracticeQuestionView {
        val userId = localUserService.localUserId()
        return inTransaction {
            // KTD19: the set row lock serializes adds, so the count below is the committed count.
            val set = findSet(userId, "id = ? FOR UPDATE", setId) ?: notFound()
            val current = view(userId, set)
            if (current.status == PracticeSetStatus.GENERATING) {
                throw ApiRequestException(HttpStatus.CONFLICT, "PRACTICE_SET_NOT_READY", "Practice questions are still being generated")
            }
            val userQuestions = current.questions.count { it.origin == USER }
            if (userQuestions >= MAX_USER_QUESTIONS) {
                throw ApiRequestException(HttpStatus.CONFLICT, "QUESTION_LIMIT_REACHED", "A practice set can have at most $MAX_USER_QUESTIONS of your own questions")
            }
            val questionId = jdbcTemplate.queryForObject(
                """
                    INSERT INTO ai_interview_app.practice_questions (practice_set_id, user_id, origin, order_index, text)
                    VALUES (?, ?, 'USER', ?, ?)
                    RETURNING id
                """.trimIndent(),
                UUID::class.java, setId, userId, userQuestions + 1, text,
            )
            jdbcTemplate.update("UPDATE ai_interview_app.practice_sets SET updated_at = now() WHERE id = ? AND user_id = ?", setId, userId)
            questions(userId, setId).single { it.id == questionId }
        }
    }

    /** The set's questions: AI questions first, then user questions in the order they were added. */
    fun questions(userId: UUID, setId: UUID): List<PracticeQuestionView> = jdbcTemplate.query(
        """
            SELECT id, origin, text, rationale, category, expected_signals::text AS expected_signals
            FROM ai_interview_app.practice_questions
            WHERE practice_set_id = ? AND user_id = ?
            ORDER BY origin = 'USER', order_index
        """.trimIndent(),
        RowMapper { rs, row ->
            PracticeQuestionView(
                rs.getObject("id", UUID::class.java), row + 1, rs.getString("origin"), rs.getString("text"),
                rs.getString("rationale"), rs.getString("category"),
                objectMapper.readValue(rs.getString("expected_signals"), Array<String>::class.java).toList(),
            )
        },
        setId, userId,
    )

    private fun startGeneration(set: SetRow) {
        requestGuard.assertAiAllowed(JobSubmissionService.AI_JOB_ACTION)
        jobSubmissionService.createOrReuse(
            JobType.PRACTICE_QUESTIONS,
            RESOURCE,
            set.id,
            PracticeQuestionsPayload(set.id, set.resumeId, set.targetJobId),
            jobSubmissionService.fingerprint(JobType.PRACTICE_QUESTIONS.name, set.id),
        )
    }

    // KTD4: the status is derived. AI questions mean READY; otherwise the latest generation job decides.
    private fun view(userId: UUID, set: SetRow): PracticeSetView {
        val questions = questions(userId, set.id)
        val job = backgroundJobStore.findLatestForResource(userId, RESOURCE, set.id, listOf(JobType.PRACTICE_QUESTIONS)).orElse(null)
        val status = when {
            questions.any { it.origin == AI } -> PracticeSetStatus.READY
            job?.status == JobStatus.FAILED -> PracticeSetStatus.FAILED
            else -> PracticeSetStatus.GENERATING
        }
        return PracticeSetView(set.id, set.resumeId, set.targetJobId, set.mode, status, questions, job?.let(ActiveJob::from), set.createdAt, set.updatedAt)
    }

    /** Locks the pair's rows against deletion and returns whether the resume is READY. */
    private fun lockInputs(userId: UUID, resumeId: UUID, targetJobId: UUID): Boolean {
        val resumeStatus = jdbcTemplate.query(
            "SELECT processing_status FROM ai_interview_app.resumes WHERE id = ? AND user_id = ? FOR KEY SHARE",
            RowMapper { rs, _ -> rs.getString("processing_status") }, resumeId, userId,
        ).firstOrNull() ?: throw ApiRequestException(HttpStatus.NOT_FOUND, "RESUME_NOT_FOUND", "Resume was not found")
        jdbcTemplate.query(
            "SELECT id FROM ai_interview_app.job_descriptions WHERE id = ? AND user_id = ? FOR KEY SHARE",
            RowMapper { rs, _ -> rs.getObject("id", UUID::class.java) }, targetJobId, userId,
        ).firstOrNull() ?: throw ApiRequestException(HttpStatus.NOT_FOUND, "TARGET_JOB_NOT_FOUND", "Target job was not found")
        return resumeStatus == "READY"
    }

    // Resume deletion takes this row FOR UPDATE, so a job created here is either deleted with the resume or never created.
    private fun lockOwner(userId: UUID) {
        jdbcTemplate.query(
            "SELECT id FROM ai_interview_app.app_users WHERE id = ? FOR KEY SHARE",
            RowMapper { rs, _ -> rs.getObject("id", UUID::class.java) }, userId,
        ).firstOrNull() ?: throw IllegalStateException("Practice set owner does not exist")
    }

    private fun findPairSet(userId: UUID, resumeId: UUID, targetJobId: UUID) =
        findSet(userId, "resume_id = ? AND target_job_id = ?", resumeId, targetJobId)

    private fun findSet(userId: UUID, condition: String, vararg arguments: Any): SetRow? = jdbcTemplate.query(
        "SELECT id, resume_id, target_job_id, mode, created_at, updated_at FROM ai_interview_app.practice_sets WHERE user_id = ? AND $condition",
        RowMapper { rs, _ ->
            SetRow(
                rs.getObject("id", UUID::class.java), rs.getObject("resume_id", UUID::class.java),
                rs.getObject("target_job_id", UUID::class.java), rs.getString("mode"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
            )
        },
        userId, *arguments,
    ).firstOrNull()

    private fun <T> inTransaction(work: () -> T): T = requireNotNull(transactionOperations.execute { work() })

    private fun notFound(): Nothing = throw ApiRequestException(HttpStatus.NOT_FOUND, "PRACTICE_SET_NOT_FOUND", "Practice set was not found")

    private data class SetRow(
        val id: UUID, val resumeId: UUID, val targetJobId: UUID, val mode: String, val createdAt: Instant, val updatedAt: Instant,
    )

    companion object {
        const val RESOURCE = "practice-set"
        const val AI = "AI"
        const val USER = "USER"
        const val MAX_USER_QUESTIONS = 10
    }
}
