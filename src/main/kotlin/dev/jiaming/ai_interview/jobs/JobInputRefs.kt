package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID

@JvmRecord
data class JobInputRefs(val resumeId: UUID?, val targetJobId: UUID?, val practiceSetId: UUID?, val attemptId: UUID?) {
    companion object {
        // Resource types whose resource_id is a resume; legacy answer-feedback jobs stored the resume there too.
        val RESUME_RESOURCE_TYPES = setOf("resume", "interview-answer")

        @JvmStatic fun from(job: BackgroundJob): JobInputRefs = from(job.requestPayload, job.resourceId, job.resourceType)

        internal fun from(payload: JsonNode?, resourceId: UUID?, resourceType: String?): JobInputRefs {
            val resumeId = uuid(payload, "resumeId") ?: resourceId?.takeIf { resourceType != null && resourceType in RESUME_RESOURCE_TYPES }
            val targetJobId = uuid(payload, "targetJobId") ?: uuid(payload, "jobDescriptionId")
            return JobInputRefs(resumeId, targetJobId, uuid(payload, "practiceSetId"), uuid(payload, "attemptId"))
        }

        private fun uuid(payload: JsonNode?, field: String): UUID? {
            if (payload == null || !payload.hasNonNull(field) || payload.get(field).asText().isBlank()) return null
            return try { UUID.fromString(payload.get(field).asText()) } catch (_: IllegalArgumentException) { null }
        }
    }
}
