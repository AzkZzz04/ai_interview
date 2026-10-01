package dev.jiaming.ai_interview.experience

import com.fasterxml.jackson.databind.JsonNode
import dev.jiaming.ai_interview.coach.AiResumeCoachService
import dev.jiaming.ai_interview.jobs.JobExecutionContext
import dev.jiaming.ai_interview.jobs.JobHandler
import dev.jiaming.ai_interview.jobs.JobStage
import dev.jiaming.ai_interview.jobs.JobType
import org.springframework.stereotype.Component

@Component
class ExperienceSplitJobHandler(
    private val coach: AiResumeCoachService,
    private val experienceService: ExperienceService
) : JobHandler<ExperienceSplitJobPayload> {
    override fun type() = JobType.EXPERIENCE_SPLIT
    override fun payloadType() = ExperienceSplitJobPayload::class.java

    override fun handle(payload: ExperienceSplitJobPayload, context: JobExecutionContext): JsonNode {
        val result = context.checkpoint("result", ExperienceSplitResult::class.java) ?: run {
            context.stage(JobStage.SPLITTING_EXPERIENCE)
            val generated = context.checkpoint("split", ExperienceSplitResult::class.java)
                ?: coach.splitExperience(payload.text).also { context.saveCheckpoint("split", it) }
            experienceService.annotateDuplicates(context.userId(), generated).also {
                context.saveCheckpoint("result", it)
            }
        }
        return context.toJson(result)
    }
}
