package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.util.LinkedHashMap
import java.util.UUID

@JvmRecord
data class JobStatusResponse(
    val jobId: UUID, val jobType: JobType, val status: JobStatus, val stage: JobStage, val attempts: Int, val maxAttempts: Int,
    val result: Any?, val error: JobErrorResponse?, val createdAt: Instant?, val startedAt: Instant?,
    val completedAt: Instant?, val inputRefs: JobInputRefs
) {
    companion object {
        @JvmStatic fun from(job: BackgroundJob): JobStatusResponse {
            return JobStatusResponse(job.id, job.jobType, job.status, job.stage, job.attempts, job.maxAttempts, jsonValue(job.resultPayload), JobErrorResponse.from(job),
                job.createdAt, job.startedAt, job.completedAt, JobInputRefs.from(job))
        }

        internal fun jsonValue(node: JsonNode?): Any? {
            if (node == null || node.isNull || node.isMissingNode) return null
            if (node.isObject) return LinkedHashMap<String, Any?>().also { value ->
                node.properties().forEach { entry -> value[entry.key] = jsonValue(entry.value) }
            }
            if (node.isArray) return node.map(::jsonValue)
            if (node.isNumber) return node.numberValue()
            if (node.isBoolean) return node.booleanValue()
            return node.asText()
        }
    }
}
