package dev.jiaming.ai_interview.storage

@JvmRecord
data class StoredObject(val bucket: String, val key: String, val sizeBytes: Long)
