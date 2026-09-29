package dev.jiaming.ai_interview.rag

import java.time.Instant
import java.util.UUID

@JvmRecord
data class RagDocumentIndex(
    val indexId: UUID, val identity: RagDocumentIndexIdentity, val status: RagDocumentIndexStatus,
    val claimVersion: Long, val indexingStartedAt: Instant?, val documentCount: Int,
    val updatedAt: Instant, val lastUsedAt: Instant
)
