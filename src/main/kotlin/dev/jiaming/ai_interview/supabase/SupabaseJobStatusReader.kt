package dev.jiaming.ai_interview.supabase

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.jobs.JobErrorResponse
import dev.jiaming.ai_interview.jobs.JobInputRefs
import dev.jiaming.ai_interview.jobs.JobStage
import dev.jiaming.ai_interview.jobs.JobStatus
import dev.jiaming.ai_interview.jobs.JobStatusReader
import dev.jiaming.ai_interview.jobs.JobStatusResponse
import dev.jiaming.ai_interview.jobs.JobType
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import java.time.Instant
import java.util.UUID

class SupabaseJobStatusReader(private val client: SupabaseClient, private val objectMapper: ObjectMapper) : JobStatusReader {
    override fun findForUser(jobId: UUID, userId: UUID): JobStatusResponse? = try {
        runBlocking {
            val data = client.postgrest.from(SCHEMA, TABLE).select(columns = Columns.raw(COLUMNS)) {
                filter {
                    eq("id", jobId.toString())
                    eq("user_id", userId.toString())
                }
                limit(1)
            }.data
            val rows = objectMapper.readTree(data) ?: invalidResponse()
            if (!rows.isArray) invalidResponse()
            if (rows.size() == 0) return@runBlocking null
            if (rows.size() != 1) invalidResponse()
            toResponse(rows[0], jobId, userId)
        }
    } catch (_: Exception) {
        throw ApiRequestException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", UNAVAILABLE_MESSAGE)
    }

    private fun toResponse(row: JsonNode, requestedJobId: UUID, requestedUserId: UUID): JobStatusResponse {
        if (!row.isObject) invalidResponse()
        val jobId = uuid(row, "id")
        val userId = uuid(row, "user_id")
        if (jobId != requestedJobId || userId != requestedUserId) invalidResponse()
        val resultPayload = requiredNode(row, "result_payload")
        val requestPayload = requiredNode(row, "request_payload")
        val lastError = nullableString(row, "last_error")
        val errorCode = nullableString(row, "error_code")
        val retryable = nullableBoolean(row, "retryable")
        val error = lastError?.let { JobErrorResponse(errorCode, it, retryable) }
        return JobStatusResponse(
            jobId,
            JobType.valueOf(requiredString(row, "job_type")),
            JobStatus.valueOf(requiredString(row, "status")),
            JobStage.valueOf(requiredString(row, "stage")),
            requiredNode(row, "attempts").takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue() ?: invalidResponse(),
            JobStatusResponse.jsonValue(resultPayload), error, nullableInstant(row, "created_at"),
            nullableInstant(row, "started_at"), nullableInstant(row, "completed_at"), JobInputRefs.from(requestPayload, nullableUuid(row, "resource_id"))
        )
    }

    private fun requiredNode(row: JsonNode, field: String): JsonNode = row.get(field) ?: invalidResponse()
    private fun requiredString(row: JsonNode, field: String): String =
        requiredNode(row, field).takeIf { it.isTextual }?.textValue() ?: invalidResponse()
    private fun nullableString(row: JsonNode, field: String): String? = requiredNode(row, field).let {
        if (it.isNull) null else it.takeIf { node -> node.isTextual }?.textValue() ?: invalidResponse()
    }
    private fun nullableBoolean(row: JsonNode, field: String): Boolean? = requiredNode(row, field).let {
        if (it.isNull) null else it.takeIf { node -> node.isBoolean }?.booleanValue() ?: invalidResponse()
    }
    private fun uuid(row: JsonNode, field: String): UUID = try {
        UUID.fromString(requiredString(row, field))
    } catch (_: IllegalArgumentException) {
        invalidResponse()
    }
    private fun nullableUuid(row: JsonNode, field: String): UUID? = nullableString(row, field)?.let {
        try { UUID.fromString(it) } catch (_: IllegalArgumentException) { invalidResponse() }
    }
    private fun nullableInstant(row: JsonNode, field: String): Instant? = nullableString(row, field)?.let {
        try { Instant.parse(it) } catch (_: Exception) { invalidResponse() }
    }
    private fun invalidResponse(): Nothing = throw IllegalStateException("Invalid job status response")

    private companion object {
        const val SCHEMA = "ai_interview_api"
        const val TABLE = "job_status"
        const val COLUMNS = "id,user_id,job_type,status,stage,attempts,result_payload,last_error,error_code,retryable,created_at,started_at,completed_at,resource_id,request_payload"
        const val UNAVAILABLE_MESSAGE = "Job status is temporarily unavailable"
    }
}
