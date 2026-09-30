package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID

@JvmRecord
data class JobInputRefs(val resumeId: UUID?, val jobDescriptionId: UUID?) {
    companion object {
        @JvmStatic fun from(job: BackgroundJob): JobInputRefs = from(job.requestPayload, job.resourceId)

        internal fun from(payload: JsonNode?, resourceId: UUID?): JobInputRefs {
            var resumeId = uuid(payload, "resumeId")
            val jobDescriptionId = uuid(payload, "jobDescriptionId")
            if (resumeId == null) resumeId = resourceId
            return JobInputRefs(resumeId, jobDescriptionId)
        }

        private fun uuid(payload: JsonNode?, field: String): UUID? {
            if (payload == null || !payload.hasNonNull(field) || payload.get(field).asText().isBlank()) return null
            return try { UUID.fromString(payload.get(field).asText()) } catch (_: IllegalArgumentException) { null }
        }
    }
}
