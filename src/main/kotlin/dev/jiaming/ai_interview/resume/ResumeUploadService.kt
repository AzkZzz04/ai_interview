package dev.jiaming.ai_interview.resume

import dev.jiaming.ai_interview.common.RedisRequestGuard
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException

@Service
class ResumeUploadService private constructor(
    private val validator: ResumeFileValidator,
    private val fileReader: ResumeFileReader,
    private val textExtractor: ResumeTextExtractor,
    private val normalizer: ResumeTextNormalizer,
    private val chunker: SectionAwareTextChunker,
    private val storageService: ResumeStorageService?,
    private val redisRequestGuard: RedisRequestGuard?,
    private val resumePersistenceService: ResumePersistenceService?
) {
    @Autowired
    constructor(
        validator: ResumeFileValidator,
        fileReader: ResumeFileReader,
        textExtractor: ResumeTextExtractor,
        normalizer: ResumeTextNormalizer,
        chunker: SectionAwareTextChunker,
        storageService: ResumeStorageService,
        redisRequestGuard: RedisRequestGuard,
        resumePersistenceServiceProvider: ObjectProvider<ResumePersistenceService>
    ) : this(validator, fileReader, textExtractor, normalizer, chunker, storageService, redisRequestGuard,
        resumePersistenceServiceProvider.ifAvailable)

    constructor(normalizer: ResumeTextNormalizer, chunker: SectionAwareTextChunker) : this(
        ResumeFileValidator(), ResumeFileReader(), ResumeTextExtractor(), normalizer, chunker, null, null, null
    )

    fun process(file: MultipartFile): ResumeUploadResponse {
        validator.validate(file)
        val startedAt = System.nanoTime()
        val fileContent = fileReader.read(file)
        val guard = redisRequestGuard
        if (guard != null) {
            return guard.withIdempotentRetryCache(
                "resume-upload", uploadFingerprint(fileContent), ResumeUploadResponse::class.java
            ) {
                guard.assertUploadAllowed()
                process(fileContent, startedAt)
            }
        }
        return process(fileContent, startedAt)
    }

    private fun process(fileContent: ResumeFileContent, startedAt: Long): ResumeUploadResponse {
        log.info("resume_upload_start filename={} sizeBytes={} contentType={} detectedContentType={}",
            fileContent.originalFilename, fileContent.sizeBytes, fileContent.contentType, fileContent.detectedContentType)
        val extractStartedAt = System.nanoTime()
        val rawText = textExtractor.extract(fileContent)
        val extractMillis = elapsedMillis(extractStartedAt)
        val normalizeStartedAt = System.nanoTime()
        val normalizedText = normalizer.normalize(rawText)
        val normalizeMillis = elapsedMillis(normalizeStartedAt)
        if (normalizedText.isBlank()) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "No readable resume text was extracted")
        }
        val chunkStartedAt = System.nanoTime()
        val chunks = chunksFor(normalizedText)
        val chunkMillis = elapsedMillis(chunkStartedAt)
        val storageKey = storageService?.store(fileContent)
        val response = resumePersistenceService?.save(
            fileContent.originalFilename, fileContent.contentType, fileContent.detectedContentType,
            fileContent.sizeBytes, storageKey, rawText, normalizedText, chunks
        ) ?: ResumeUploadResponse(
            UUID.randomUUID().toString(), fileContent.originalFilename, fileContent.contentType,
            fileContent.detectedContentType, fileContent.sizeBytes, rawText.length, normalizedText.length,
            normalizedText, chunks, Instant.now()
        )
        if (storageKey != null) storageService.markReady(storageKey)
        log.info("resume_upload_complete filename={} storageKey={} extractMs={} normalizeMs={} chunkMs={} totalMs={} chunks={} rawChars={} normalizedChars={}",
            fileContent.originalFilename, storageKey, extractMillis, normalizeMillis, chunkMillis,
            elapsedMillis(startedAt), chunks.size, rawText.length, normalizedText.length)
        return response
    }

    fun chunksFor(normalizedText: String): List<ResumeChunkResponse> = chunker.chunk(normalizedText).map {
        ResumeChunkResponse(it.index, it.section, it.content, it.content.length)
    }

    private fun elapsedMillis(startedAt: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private fun uploadFingerprint(fileContent: ResumeFileContent) = UploadFingerprint(
        fileContent.originalFilename, fileContent.contentType, fileContent.detectedContentType,
        fileContent.sizeBytes, sha256(fileContent.bytes)
    )

    private fun sha256(bytes: ByteArray): String = try {
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    } catch (exception: NoSuchAlgorithmException) {
        throw IllegalStateException("SHA-256 is unavailable", exception)
    }

    private data class UploadFingerprint(
        val originalFilename: String?, val contentType: String?, val detectedContentType: String?,
        val sizeBytes: Long, val contentHash: String
    )

    private companion object { val log = LoggerFactory.getLogger(ResumeUploadService::class.java) }
}
