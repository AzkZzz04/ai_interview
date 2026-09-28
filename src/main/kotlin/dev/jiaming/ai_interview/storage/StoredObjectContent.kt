package dev.jiaming.ai_interview.storage

@JvmRecord
data class StoredObjectContent(val bytes: ByteArray, val contentType: String?, val metadata: Map<String, String>?)
