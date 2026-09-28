package dev.jiaming.ai_interview.resume

import dev.jiaming.ai_interview.storage.ObjectStorageService
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ResumeStorageService(objectStorageServiceProvider: ObjectProvider<ObjectStorageService>) {
    private val objectStorageService = objectStorageServiceProvider.ifAvailable

    fun store(fileContent: ResumeFileContent): String {
        val storage = objectStorageService
            ?: throw IllegalStateException("Object storage is required for asynchronous resume extraction")
        return storage.put(
            storageKey(fileContent.originalFilename), fileContent.bytes, fileContent.detectedContentType,
            mapOf("original-filename" to safeMetadata(fileContent.originalFilename)),
            mapOf("processing-status" to "pending")
        ).key
    }

    fun read(payload: ResumeExtractionJobPayload): ResumeFileContent {
        val storage = objectStorageService
            ?: throw IllegalStateException("Object storage is required for asynchronous resume extraction")
        val storedObject = storage.get(payload.storageKey)
        return ResumeFileContent(
            payload.originalFilename, payload.contentType, storedObject.bytes.size.toLong(), storedObject.bytes,
            payload.detectedContentType, payload.extension
        )
    }

    fun delete(storageKey: String) { objectStorageService?.delete(storageKey) }

    fun markReady(storageKey: String) {
        val storage = objectStorageService
            ?: throw IllegalStateException("Object storage is required for asynchronous resume extraction")
        storage.tag(storageKey, mapOf("processing-status" to "ready"))
    }

    private fun storageKey(filename: String?) = "resumes/%s/%s".format(UUID.randomUUID(), safeFilename(filename))

    private fun safeFilename(filename: String?): String {
        if (filename.isNullOrBlank()) return "resume"
        return filename.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "resume" }
    }

    private fun safeMetadata(value: String?) = if (value.isNullOrBlank()) "unknown"
        else value.replace(Regex("[^\\x20-\\x7E]"), "_")
}
