package dev.jiaming.ai_interview.document

import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.ContentHasher
import dev.jiaming.ai_interview.interview.JobDescriptionPersistenceService
import dev.jiaming.ai_interview.resume.ResumePersistenceService
import dev.jiaming.ai_interview.resume.ResumeTextNormalizer
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.Optional
import java.util.UUID

@Service
class DocumentReferenceResolver(
    private val resumePersistenceService: ResumePersistenceService,
    private val jobDescriptionPersistenceService: JobDescriptionPersistenceService,
    private val normalizer: ResumeTextNormalizer,
    private val contentHasher: ContentHasher,
) {
    @Transactional
    fun resolveForSubmission(
        userId: UUID,
        resumeId: UUID?,
        resumeText: String?,
        jobDescriptionId: UUID?,
        jobDescription: String?,
    ): ResolvedJobInputs = resolve(userId, resumeId, resumeText, jobDescriptionId, jobDescription)

    @Transactional
    fun resolveLegacy(
        userId: UUID,
        resumeId: UUID?,
        resumeText: String?,
        jobDescriptionId: UUID?,
        jobDescription: String?,
    ): ResolvedJobInputs = resolve(userId, resumeId, resumeText, jobDescriptionId, jobDescription)

    @Transactional(readOnly = true)
    fun resolveStrict(userId: UUID, resumeId: UUID?, jobDescriptionId: UUID?): ResolvedJobInputs {
        if (resumeId == null) {
            throw ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "RESUME_REFERENCE_REQUIRED",
                "The background job does not contain a resume reference",
            )
        }
        val resume = resolveResume(userId, resumeId, null, false)
        val jobDescription = if (jobDescriptionId == null) Optional.empty() else
            Optional.of(resolveJobDescription(userId, jobDescriptionId, null).orElseThrow())
        return ResolvedJobInputs(resume, jobDescription)
    }

    private fun resolveResume(userId: UUID, resumeId: UUID?, resumeText: String?, allowLatest: Boolean): ResolvedDocument {
        if (resumeId != null) {
            val status = resumePersistenceService.findProcessingStatus(userId, resumeId).orElseThrow {
                ApiRequestException(HttpStatus.NOT_FOUND, "RESUME_NOT_FOUND", "Resume was not found")
            }
            if (status != "READY") {
                throw ApiRequestException(HttpStatus.CONFLICT, "RESUME_NOT_READY", "Resume is not ready for analysis")
            }
            val document = resumePersistenceService.findReadyDocument(userId, resumeId).orElseThrow {
                ApiRequestException(HttpStatus.CONFLICT, "RESUME_NOT_READY", "Resume is not ready for analysis")
            }
            assertMatchingText(document, resumeText)
            return document
        }

        val normalizedText = normalizer.normalize(resumeText)
        if (!normalizedText.isBlank()) return resumePersistenceService.findOrCreateDocument(userId, resumeText!!)
        if (allowLatest) return resumePersistenceService.findLatestReadyDocument(userId).orElseThrow(::missingResume)
        throw missingResume()
    }

    private fun resolve(
        userId: UUID,
        resumeId: UUID?,
        resumeText: String?,
        jobDescriptionId: UUID?,
        jobDescription: String?,
    ) = ResolvedJobInputs(
        resolveResume(userId, resumeId, resumeText, true),
        resolveJobDescription(userId, jobDescriptionId, jobDescription),
    )

    private fun resolveJobDescription(
        userId: UUID,
        jobDescriptionId: UUID?,
        jobDescription: String?,
    ): Optional<ResolvedDocument> {
        if (jobDescriptionId != null) {
            val document = jobDescriptionPersistenceService.findDocument(userId, jobDescriptionId).orElseThrow {
                ApiRequestException(
                    HttpStatus.NOT_FOUND,
                    "JOB_DESCRIPTION_NOT_FOUND",
                    "Job description was not found",
                )
            }
            assertMatchingText(document, jobDescription)
            return Optional.of(document)
        }

        if (normalizer.normalize(jobDescription).isBlank()) return Optional.empty()
        return Optional.of(jobDescriptionPersistenceService.findOrCreateDocument(userId, jobDescription!!))
    }

    private fun assertMatchingText(document: ResolvedDocument, suppliedText: String?) {
        if (suppliedText == null || suppliedText.isBlank()) return
        val normalizedText = normalizer.normalize(suppliedText)
        val suppliedHash = contentHasher.sha256(normalizedText)
        if (document.contentHash() != suppliedHash || document.normalizedText() != normalizedText) {
            throw ApiRequestException(
                HttpStatus.CONFLICT,
                "REFERENCE_MISMATCH",
                "The supplied text does not match the referenced document",
            )
        }
    }

    private fun missingResume() = ApiRequestException(
        HttpStatus.BAD_REQUEST,
        "RESUME_TEXT_REQUIRED",
        "Resume text is required. Paste text or upload a resume first.",
    )
}
