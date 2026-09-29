package dev.jiaming.ai_interview.rag

import java.util.UUID

@JvmRecord
data class RagDocumentIndexHandle(val indexId: UUID, val claimVersion: Long)
