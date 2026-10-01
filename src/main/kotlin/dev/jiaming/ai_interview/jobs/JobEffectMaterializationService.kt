package dev.jiaming.ai_interview.jobs

import java.util.UUID
import java.util.function.Consumer
import java.util.function.Supplier
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import dev.jiaming.ai_interview.coach.AnswerFeedbackResponse
import dev.jiaming.ai_interview.coach.AssessmentResponse
import dev.jiaming.ai_interview.coach.InterviewQuestionsResponse
import dev.jiaming.ai_interview.interview.AnalysisPersistenceInput
import dev.jiaming.ai_interview.interview.FeedbackPersistenceInput
import dev.jiaming.ai_interview.interview.InterviewPersistenceService
import dev.jiaming.ai_interview.score.ResumeScoreResult

@Service
class JobEffectMaterializationService(private val jdbcTemplate: JdbcTemplate,
                                      private val interviewPersistenceService: InterviewPersistenceService,
                                      private val objectMapper: ObjectMapper) {
    @Transactional
    fun materializeAssessment(job: BackgroundJob, leaseToken: UUID, input: AnalysisPersistenceInput, response: AssessmentResponse): UUID {
        lockOwnedLease(job.id, leaseToken)
        return materialize(job.id, JobEffectType.ASSESSMENT) { id -> interviewPersistenceService.saveAssessment(id, input, response) }
    }
    @Transactional
    fun materializeQuestions(job: BackgroundJob, leaseToken: UUID, input: AnalysisPersistenceInput, response: InterviewQuestionsResponse): UUID {
        lockOwnedLease(job.id, leaseToken)
        val assessmentId = requireEffect(job.id, JobEffectType.ASSESSMENT)
        return materialize(job.id, JobEffectType.QUESTIONS) { id -> interviewPersistenceService.saveQuestions(id, assessmentId, input, response) }
    }
    @Transactional
    fun materializeFeedback(job: BackgroundJob, leaseToken: UUID, input: FeedbackPersistenceInput, response: AnswerFeedbackResponse): UUID {
        lockOwnedLease(job.id, leaseToken)
        return materialize(job.id, JobEffectType.ANSWER_FEEDBACK) { id -> interviewPersistenceService.saveAnswer(id, input, response) }
    }
    @Transactional
    fun materializeResumeScore(job: BackgroundJob, leaseToken: UUID, resumeId: UUID, response: ResumeScoreResult): UUID {
        lockOwnedLease(job.id, leaseToken)
        val userId = job.userId ?: throw IllegalArgumentException("Background job has no user: ${job.id}")
        return materialize(job.id, JobEffectType.RESUME_SCORE) { id ->
            val resultJson = try { objectMapper.writeValueAsString(response) }
            catch (exception: JsonProcessingException) { throw IllegalStateException("Could not serialize resume score", exception) }
            jdbcTemplate.update(
                """
                    INSERT INTO ai_interview_app.resume_scores (id, user_id, resume_id, job_title, overall, result, scored_at)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """.trimIndent(),
                id, userId, resumeId, response.jobTitle, response.overall, resultJson, java.sql.Timestamp.from(response.scoredAt)
            )
        }
    }
    @Transactional
    fun <T> withOwnedLease(job: BackgroundJob, leaseToken: UUID, work: Supplier<T>): T {
        lockOwnedLease(job.id, leaseToken)
        return work.get()
    }
    private fun materialize(jobId: UUID, effectType: JobEffectType, writer: Consumer<UUID>): UUID {
        findEffect(jobId, effectType)?.let { return it }
        val resourceId = UUID.randomUUID()
        val inserted = jdbcTemplate.update("""
            INSERT INTO ai_interview_app.background_job_effects (job_id, effect_type, resource_id)
            VALUES (?, ?, ?)
            ON CONFLICT (job_id, effect_type) DO NOTHING
            """, jobId, effectType.name, resourceId)
        if (inserted == 0) return requireEffect(jobId, effectType)
        writer.accept(resourceId)
        return resourceId
    }
    private fun lockOwnedLease(jobId: UUID, leaseToken: UUID) {
        val jobs = jdbcTemplate.query("""
            SELECT id FROM ai_interview_app.background_jobs
            WHERE id = ? AND status = 'PROCESSING' AND lease_token = ? AND lease_expires_at > now()
            FOR UPDATE
            """, { rs, _ -> rs.getObject("id", UUID::class.java) }, jobId, leaseToken)
        if (jobs.isEmpty()) throw JobLeaseLostException(jobId)
    }
    private fun requireEffect(jobId: UUID, effectType: JobEffectType): UUID = findEffect(jobId, effectType)
        ?: throw IllegalStateException("Missing $effectType effect for job $jobId")
    private fun findEffect(jobId: UUID, effectType: JobEffectType): UUID? = jdbcTemplate.query(
        "SELECT resource_id FROM ai_interview_app.background_job_effects WHERE job_id = ? AND effect_type = ?",
        { rs, _ -> rs.getObject("resource_id", UUID::class.java) }, jobId, effectType.name
    ).firstOrNull()
}
