package dev.jiaming.ai_interview.document

@JvmRecord
data class DocumentChunk(
    val index: Int,
    val section: String,
    val content: String,
    val contextId: String,
)
