package dev.jiaming.ai_interview.rag

import dev.jiaming.ai_interview.document.DocumentSourceType

@JvmRecord
data class RagDocumentIndexIdentity(
    val sourceType: DocumentSourceType, val contentHash: String, val embeddingModel: String,
    val embeddingDimensions: Int, val chunkSchema: String
)
