package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

@JvmRecord
data class BackgroundJob(
    val id: UUID, val userId: UUID?, val jobType: JobType, val resourceType: String?, val resourceId: UUID?,
    val status: JobStatus, val stage: JobStage, val requestPayload: JsonNode?, val resultPayload: JsonNode?,
    val requestFingerprint: String?, val attempts: Int, val maxAttempts: Int, val errorCode: String?,
    val lastError: String?, val retryable: Boolean?, val runAfter: Instant?, val createdAt: Instant,
    val updatedAt: Instant, val enqueuedAt: Instant?, val startedAt: Instant?, val completedAt: Instant?,
    val leaseToken: UUID?, val leaseExpiresAt: Instant?
) {
    fun requireUserId(): UUID = userId ?: throw IllegalArgumentException("Background job has no user: $id")
}
