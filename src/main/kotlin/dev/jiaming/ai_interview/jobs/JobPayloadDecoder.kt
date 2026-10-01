package dev.jiaming.ai_interview.jobs

import java.util.UUID
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import dev.jiaming.ai_interview.coach.AiAnalysisRequest
import dev.jiaming.ai_interview.coach.AnswerFeedbackRequest
import dev.jiaming.ai_interview.document.DocumentReferenceResolver
import dev.jiaming.ai_interview.document.ResolvedJobInputs
import dev.jiaming.ai_interview.experience.ExperienceSplitJobPayload
import dev.jiaming.ai_interview.resume.ResumeExtractionJobPayload
import dev.jiaming.ai_interview.score.ResumeScorePayload

@Component
class JobPayloadDecoder(private val objectMapper: ObjectMapper, private val jobStore: BackgroundJobStore,
                        private val documentResolver: DocumentReferenceResolver) {
    fun analysis(job: BackgroundJob, leaseToken: UUID): AnalysisJobPayload {
        if (isCurrent(job.requestPayload, AnalysisJobPayload.CURRENT_VERSION)) return convert(job.requestPayload, AnalysisJobPayload::class.java)
        val legacy = convert(job.requestPayload, AiAnalysisRequest::class.java)
        val inputs = documentResolver.resolveLegacy(requireUser(job), legacy.resumeId, legacy.resumeText, legacy.jobDescriptionId, legacy.jobDescription)
        val upgraded = AnalysisJobPayload(inputs.resume().resourceId(), inputs.jobDescription().map { it.resourceId() }.orElse(null), legacy.targetRole, legacy.seniority)
        jobStore.replaceRequestPayload(job.id, leaseToken, objectMapper.valueToTree(upgraded))
        return upgraded
    }

    fun feedback(job: BackgroundJob, leaseToken: UUID): FeedbackJobPayload {
        if (isCurrent(job.requestPayload, FeedbackJobPayload.CURRENT_VERSION)) return convert(job.requestPayload, FeedbackJobPayload::class.java)
        val legacy = convert(job.requestPayload, AnswerFeedbackRequest::class.java)
        val inputs = documentResolver.resolveLegacy(requireUser(job), legacy.resumeId, legacy.resumeText, legacy.jobDescriptionId, legacy.jobDescription)
        val upgraded = FeedbackJobPayload(inputs.resume().resourceId(), inputs.jobDescription().map { it.resourceId() }.orElse(null),
            legacy.targetRole, legacy.seniority, legacy.questionText, legacy.category, legacy.expectedSignals, legacy.answerText)
        jobStore.replaceRequestPayload(job.id, leaseToken, objectMapper.valueToTree(upgraded))
        return upgraded
    }

    fun resumeScore(job: BackgroundJob): ResumeScorePayload {
        val payload = convert(job.requestPayload, ResumeScorePayload::class.java)
        require(payload.payloadVersion == ResumeScorePayload.CURRENT_VERSION) { "Unsupported resume score payload version ${payload.payloadVersion}" }
        return payload
    }

    fun jobFit(job: BackgroundJob): JobFitPayload {
        if (!isCurrent(job.requestPayload, JobFitPayload.CURRENT_VERSION)) {
            throw IllegalArgumentException("Invalid ${JobFitPayload::class.java.simpleName} job payload")
        }
        return convert(job.requestPayload, JobFitPayload::class.java)
    }

    fun decode(job: BackgroundJob, leaseToken: UUID, payloadType: Class<*>): Any {
        val payload: Any = when (job.jobType) {
            JobType.RESUME_EXTRACTION -> convert(job.requestPayload, ResumeExtractionJobPayload::class.java)
            JobType.RESUME_SCORE -> resumeScore(job)
            JobType.ANALYSIS -> analysis(job, leaseToken)
            JobType.ANSWER_FEEDBACK -> feedback(job, leaseToken)
            JobType.EXPERIENCE_SPLIT -> convert(job.requestPayload, ExperienceSplitJobPayload::class.java)
            JobType.JOB_FIT -> jobFit(job)
        }
        require(payloadType.isInstance(payload)) { "Decoded payload for ${job.jobType} is not ${payloadType.simpleName}" }
        return payload
    }

    private fun isCurrent(payload: JsonNode?, version: Int) = payload != null && payload.path("payloadVersion").asInt(0) == version
    private fun requireUser(job: BackgroundJob): UUID = job.userId ?: throw IllegalArgumentException("Background job has no user: ${job.id}")
    private fun <T> convert(node: JsonNode?, type: Class<T>): T = try {
        objectMapper.treeToValue(node, type)
    } catch (exception: JsonProcessingException) {
        throw IllegalArgumentException("Invalid ${type.simpleName} job payload", exception)
    }
}
