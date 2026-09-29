package dev.jiaming.ai_interview.interview

import dev.jiaming.ai_interview.coach.AssessmentResponse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AssessmentPersistenceService(
    private val jdbcTemplate: JdbcTemplate,
    private val jsonSupport: PersistenceJsonSupport,
) {
    fun save(assessmentId: UUID, input: AnalysisPersistenceInput, response: AssessmentResponse) {
        val scores = response.scores!!
        jdbcTemplate.update(
            """
                INSERT INTO ai_interview_app.resume_assessments (
                    id, user_id, resume_id, job_description_id, overall_score,
                    technical_depth_score, impact_score, clarity_score, relevance_score, ats_score,
                    strengths, weaknesses, recommendations, model_name, prompt_name, input_hash
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?)
            """.trimIndent(),
            assessmentId,
            input.userId,
            input.resumeId,
            input.jobDescriptionId,
            response.overallScore,
            scores.technicalDepth,
            scores.impact,
            scores.clarity,
            scores.relevance,
            scores.ats,
            jsonSupport.json(response.strengths),
            jsonSupport.json(response.weaknesses),
            jsonSupport.json(response.recommendations),
            jsonSupport.model(response.modelProvider),
            jsonSupport.promptName(),
            jsonSupport.hash(input.resumeHash, input.jobDescriptionHash, input.targetRole, input.seniority),
        )
    }
}
