package dev.jiaming.ai_interview.resume

import java.time.Instant
import java.util.UUID
import org.springframework.boot.context.properties.ConfigurationProperties

@JvmRecord
data class FailedResumeJob(val resumeId: UUID, val errorCode: String?, val errorMessage: String?)

@JvmRecord
data class FailedResumeStorage(val resumeId: UUID, val storageKey: String)

@JvmRecord
data class ResumeChunkResponse(val index: Int, val section: String, val content: String, val characterCount: Int)

@JvmRecord
data class ResumeExtractionJobPayload(
    val resumeId: UUID,
    val storageKey: String,
    val originalFilename: String?,
    val contentType: String?,
    val detectedContentType: String?,
    val sizeBytes: Long,
    val extension: String
)

@ConfigurationProperties(prefix = "app.resume-extraction")
class ResumeExtractionProperties(
    queueCapacity: Int,
    timeoutSeconds: Int,
    maxParseChars: Int,
    maxPdfPages: Int,
    maxEmbeddedResources: Int
) {
    val queueCapacity = if (queueCapacity <= 0) 2 else queueCapacity
    val timeoutSeconds = if (timeoutSeconds <= 0) 20 else timeoutSeconds
    val maxParseChars = if (maxParseChars <= 0) 250_000 else maxParseChars
    val maxPdfPages = if (maxPdfPages <= 0) 50 else maxPdfPages
    val maxEmbeddedResources = if (maxEmbeddedResources <= 0) 20 else maxEmbeddedResources

}

@JvmRecord
data class ResumeFileContent(
    val originalFilename: String?,
    val contentType: String?,
    val sizeBytes: Long,
    val bytes: ByteArray,
    val detectedContentType: String?,
    val extension: String
)

@JvmRecord
data class ResumeUploadResponse(
    val id: String,
    val originalFilename: String?,
    val contentType: String?,
    val detectedContentType: String?,
    val sizeBytes: Long,
    val rawTextLength: Int,
    val normalizedTextLength: Int,
    val normalizedText: String,
    val chunks: List<ResumeChunkResponse>,
    val processedAt: Instant
)

@JvmRecord
data class TextChunk(val index: Int, val section: String, val content: String)
