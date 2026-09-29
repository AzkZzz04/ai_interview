package dev.jiaming.ai_interview.resume

import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.jobs.JobAcceptedResponse
import dev.jiaming.ai_interview.jobs.JobSubmissionService
import dev.jiaming.ai_interview.jobs.JobType
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.HexFormat
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations
import org.springframework.web.multipart.MultipartFile

@Service
class ResumeJobSubmissionService(
    private val validator: ResumeFileValidator,
    private val fileReader: ResumeFileReader,
    private val storageService: ResumeStorageService,
    private val persistenceService: ResumePersistenceService,
    private val jobSubmissionService: JobSubmissionService,
    private val requestGuard: RedisRequestGuard,
    private val transactionOperations: TransactionOperations
) {
    fun submit(file: MultipartFile): JobAcceptedResponse {
        jobSubmissionService.assertApiAvailable()
        validator.validate(file)
        val content = fileReader.read(file)
        val source = UploadFingerprint(
            content.originalFilename, content.contentType, content.detectedContentType, content.sizeBytes, sha256(content.bytes)
        )
        val fingerprint = jobSubmissionService.fingerprint("resume-upload", source)
        return jobSubmissionService.withIdempotency("resume-upload", source, JobAcceptedResponse::class.java) {
            submitNewOrReuse(content, fingerprint)
        }
    }

    private fun submitNewOrReuse(content: ResumeFileContent, fingerprint: String): JobAcceptedResponse {
        requestGuard.assertUploadAllowed()
        jobSubmissionService.findReusable(JobType.RESUME_EXTRACTION, fingerprint).orElse(null)?.let { return it }
        var storageKey: String? = null
        try {
            storageKey = storageService.store(content)
            val committedStorageKey = storageKey
            val response = requireNotNull(transactionOperations.execute {
                val resumeId = persistenceService.createPending(
                    content.originalFilename, content.contentType, content.detectedContentType,
                    content.sizeBytes, committedStorageKey
                )
                val payload = ResumeExtractionJobPayload(
                    resumeId, committedStorageKey, content.originalFilename, content.contentType,
                    content.detectedContentType, content.sizeBytes, content.extension
                )
                val accepted = jobSubmissionService.createOrReuse(
                    JobType.RESUME_EXTRACTION, "resume", resumeId, payload, fingerprint
                )
                if (accepted.reused) persistenceService.deletePending(resumeId)
                accepted
            })
            if (response.reused) deleteStorage(storageKey)
            return response
        } catch (exception: RuntimeException) {
            deleteStorage(storageKey)
            throw exception
        }
    }

    private fun deleteStorage(storageKey: String?) {
        if (storageKey != null) try {
            storageService.delete(storageKey)
        } catch (exception: RuntimeException) {
            log.warn("resume_upload_compensation_storage_failed storageKey={} reason={}", storageKey, exception.message)
        }
    }

    private fun sha256(bytes: ByteArray): String = try {
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    } catch (exception: NoSuchAlgorithmException) {
        throw IllegalStateException("SHA-256 is unavailable", exception)
    }

    private data class UploadFingerprint(
        val originalFilename: String?,
        val contentType: String?,
        val detectedContentType: String?,
        val sizeBytes: Long,
        val contentHash: String
    )

    private companion object { val log = LoggerFactory.getLogger(ResumeJobSubmissionService::class.java) }
}
