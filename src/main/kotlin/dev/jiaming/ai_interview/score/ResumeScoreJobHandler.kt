package dev.jiaming.ai_interview.score

import com.fasterxml.jackson.databind.JsonNode
import dev.jiaming.ai_interview.coach.AiResumeCoachService
import dev.jiaming.ai_interview.jobs.JobExecutionContext
import dev.jiaming.ai_interview.jobs.JobHandler
import dev.jiaming.ai_interview.jobs.JobStage
import dev.jiaming.ai_interview.jobs.JobType
import org.springframework.stereotype.Component

@Component
class ResumeScoreJobHandler(private val coachService: AiResumeCoachService) : JobHandler<ResumeScorePayload> {
    override fun type() = JobType.RESUME_SCORE
    override fun payloadType() = ResumeScorePayload::class.java

    override fun handle(payload: ResumeScorePayload, context: JobExecutionContext): JsonNode {
        context.stage(JobStage.SCORING_RESUME)
        val result = context.rootCheckpoint(ResumeScoreResult::class.java, "overall") ?: coachService.scoreResume(
            payload.resumeText, payload.jobTitle
        ).also { context.saveRootCheckpoint(it, "resume-score") }
        context.materializeResumeScore(payload.resumeId, result)
        return context.toJson(result)
    }
}
