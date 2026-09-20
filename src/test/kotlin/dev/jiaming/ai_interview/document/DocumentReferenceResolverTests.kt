package dev.jiaming.ai_interview.document

import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.ContentHasher
import dev.jiaming.ai_interview.interview.JobDescriptionPersistenceService
import dev.jiaming.ai_interview.resume.ResumePersistenceService
import dev.jiaming.ai_interview.resume.ResumeTextNormalizer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import java.util.Optional
import java.util.UUID

class DocumentReferenceResolverTests {

    private val resumes = Mockito.mock(ResumePersistenceService::class.java)
    private val jobDescriptions = Mockito.mock(JobDescriptionPersistenceService::class.java)
    private val normalizer = ResumeTextNormalizer()
    private val hasher = ContentHasher()
    private val resolver = DocumentReferenceResolver(resumes, jobDescriptions, normalizer, hasher)
    private val userId = UUID.randomUUID()

    @Test
    fun resolvesUserScopedReadyResumeById() {
        val resumeId = UUID.randomUUID()
        val resume = document(DocumentSourceType.RESUME, resumeId, "Resume text")
        Mockito.`when`(resumes.findProcessingStatus(userId, resumeId)).thenReturn(Optional.of("READY"))
        Mockito.`when`(resumes.findReadyDocument(userId, resumeId)).thenReturn(Optional.of(resume))

        val result = resolver.resolveForSubmission(userId, resumeId, null, null, null)

        assertThat(result.resume()).isEqualTo(resume)
        assertThat(result.jobDescription()).isEmpty()
    }

    @Test
    fun rejectsTextThatDoesNotMatchReferencedResume() {
        val resumeId = UUID.randomUUID()
        val resume = document(DocumentSourceType.RESUME, resumeId, "Stored text")
        Mockito.`when`(resumes.findProcessingStatus(userId, resumeId)).thenReturn(Optional.of("READY"))
        Mockito.`when`(resumes.findReadyDocument(userId, resumeId)).thenReturn(Optional.of(resume))

        assertThatThrownBy { resolver.resolveForSubmission(userId, resumeId, "Different text", null, null) }
            .isInstanceOfSatisfying(ApiRequestException::class.java) { exception ->
                assertThat(exception.code()).isEqualTo("REFERENCE_MISMATCH")
            }
    }

    @Test
    fun legacyUpgradeMayResolveLatestResumeOnceToCreateAStableReference() {
        val latest = document(DocumentSourceType.RESUME, UUID.randomUUID(), "Latest resume")
        Mockito.`when`(resumes.findLatestReadyDocument(userId)).thenReturn(Optional.of(latest))

        assertThat(resolver.resolveForSubmission(userId, null, null, null, null).resume()).isEqualTo(latest)
        assertThat(resolver.resolveLegacy(userId, null, null, null, null).resume()).isEqualTo(latest)
    }

    @Test
    fun materializesInlineDocumentsAndReturnsStableIds() {
        val resume = document(DocumentSourceType.RESUME, UUID.randomUUID(), "Resume text")
        val jd = document(DocumentSourceType.JOB_DESCRIPTION, UUID.randomUUID(), "Job text")
        Mockito.`when`(resumes.findOrCreateDocument(userId, "Resume text")).thenReturn(resume)
        Mockito.`when`(jobDescriptions.findOrCreateDocument(userId, "Job text")).thenReturn(jd)

        val result = resolver.resolveForSubmission(userId, null, "Resume text", null, "Job text")

        assertThat(result.resume().resourceId()).isEqualTo(resume.resourceId())
        assertThat(result.jobDescription()).contains(jd)
        Mockito.verify(resumes).findOrCreateDocument(userId, "Resume text")
        Mockito.verify(jobDescriptions).findOrCreateDocument(userId, "Job text")
    }

    @Test
    fun rejectsPendingResumeWithoutFallingBack() {
        val resumeId = UUID.randomUUID()
        Mockito.`when`(resumes.findProcessingStatus(userId, resumeId)).thenReturn(Optional.of("PENDING"))

        assertThatThrownBy { resolver.resolveForSubmission(userId, resumeId, null, null, null) }
            .isInstanceOfSatisfying(ApiRequestException::class.java) { exception ->
                assertThat(exception.code()).isEqualTo("RESUME_NOT_READY")
            }
    }

    @Test
    fun unknownResumeIdRaisesNotFound() {
        val resumeId = UUID.randomUUID()
        Mockito.`when`(resumes.findProcessingStatus(userId, resumeId)).thenReturn(Optional.empty())

        assertThatThrownBy { resolver.resolveForSubmission(userId, resumeId, null, null, null) }
            .isInstanceOfSatisfying(ApiRequestException::class.java) { exception ->
                assertThat(exception.code()).isEqualTo("RESUME_NOT_FOUND")
                assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND)
            }
    }

    @Test
    fun failedResumeIsRejectedWithoutFallingBack() {
        val resumeId = UUID.randomUUID()
        Mockito.`when`(resumes.findProcessingStatus(userId, resumeId)).thenReturn(Optional.of("FAILED"))

        assertThatThrownBy { resolver.resolveForSubmission(userId, resumeId, null, null, null) }
            .isInstanceOfSatisfying(ApiRequestException::class.java) { exception ->
                assertThat(exception.code()).isEqualTo("RESUME_NOT_READY")
                assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT)
            }
    }

    @Test
    fun readyStatusButMissingDocumentRaisesNotReady() {
        val resumeId = UUID.randomUUID()
        Mockito.`when`(resumes.findProcessingStatus(userId, resumeId)).thenReturn(Optional.of("READY"))
        Mockito.`when`(resumes.findReadyDocument(userId, resumeId)).thenReturn(Optional.empty())

        assertThatThrownBy { resolver.resolveForSubmission(userId, resumeId, null, null, null) }
            .isInstanceOfSatisfying(ApiRequestException::class.java) { exception ->
                assertThat(exception.code()).isEqualTo("RESUME_NOT_READY")
            }
    }

    @Test
    fun acceptsSuppliedTextThatMatchesReferencedResume() {
        val resumeId = UUID.randomUUID()
        val text = "Stored resume text"
        val resume = document(DocumentSourceType.RESUME, resumeId, normalizer.normalize(text))
        Mockito.`when`(resumes.findProcessingStatus(userId, resumeId)).thenReturn(Optional.of("READY"))
        Mockito.`when`(resumes.findReadyDocument(userId, resumeId)).thenReturn(Optional.of(resume))

        val result = resolver.resolveForSubmission(userId, resumeId, text, null, null)

        assertThat(result.resume()).isEqualTo(resume)
    }

    @Test
    fun unknownJobDescriptionIdRaisesNotFound() {
        val jobDescriptionId = UUID.randomUUID()
        val resume = document(DocumentSourceType.RESUME, UUID.randomUUID(), "Resume text")
        Mockito.`when`(resumes.findOrCreateDocument(userId, "Resume text")).thenReturn(resume)
        Mockito.`when`(jobDescriptions.findDocument(userId, jobDescriptionId)).thenReturn(Optional.empty())

        assertThatThrownBy {
            resolver.resolveForSubmission(userId, null, "Resume text", jobDescriptionId, null)
        }.isInstanceOfSatisfying(ApiRequestException::class.java) { exception ->
            assertThat(exception.code()).isEqualTo("JOB_DESCRIPTION_NOT_FOUND")
            assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND)
        }
    }

    @Test
    fun rejectsTextThatDoesNotMatchReferencedJobDescription() {
        val jobDescriptionId = UUID.randomUUID()
        val resume = document(DocumentSourceType.RESUME, UUID.randomUUID(), "Resume text")
        val jd = document(DocumentSourceType.JOB_DESCRIPTION, jobDescriptionId, "Stored JD")
        Mockito.`when`(resumes.findOrCreateDocument(userId, "Resume text")).thenReturn(resume)
        Mockito.`when`(jobDescriptions.findDocument(userId, jobDescriptionId)).thenReturn(Optional.of(jd))

        assertThatThrownBy {
            resolver.resolveForSubmission(userId, null, "Resume text", jobDescriptionId, "Different JD")
        }.isInstanceOfSatisfying(ApiRequestException::class.java) { exception ->
            assertThat(exception.code()).isEqualTo("REFERENCE_MISMATCH")
        }
    }

    @Test
    fun submissionWithNoResumeAndNoLatestRaisesTextRequired() {
        Mockito.`when`(resumes.findLatestReadyDocument(userId)).thenReturn(Optional.empty())

        assertThatThrownBy { resolver.resolveForSubmission(userId, null, "   ", null, null) }
            .isInstanceOfSatisfying(ApiRequestException::class.java) { exception ->
                assertThat(exception.code()).isEqualTo("RESUME_TEXT_REQUIRED")
                assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST)
            }
    }

    @Test
    fun preservesPersistedChunksOnResolvedResume() {
        val resumeId = UUID.randomUUID()
        val chunks = listOf(DocumentChunk(0, "Experience", "Built an API", "resume:experience:0"))
        val resume = ResolvedDocument(
            DocumentSourceType.RESUME, resumeId, hasher.sha256("Resume text"), "Resume text", chunks
        )
        Mockito.`when`(resumes.findProcessingStatus(userId, resumeId)).thenReturn(Optional.of("READY"))
        Mockito.`when`(resumes.findReadyDocument(userId, resumeId)).thenReturn(Optional.of(resume))

        val result = resolver.resolveForSubmission(userId, resumeId, null, null, null)

        assertThat(result.resume().persistedChunks()).isEqualTo(chunks)
    }

    @Test
    fun strictResolutionRequiresAResumeReference() {
        assertThatThrownBy { resolver.resolveStrict(userId, null, null) }
            .isInstanceOfSatisfying(ApiRequestException::class.java) { exception ->
                assertThat(exception.code()).isEqualTo("RESUME_REFERENCE_REQUIRED")
                assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST)
            }
    }

    @Test
    fun strictResolutionLoadsByIdAndNeverFallsBackToLatest() {
        val resumeId = UUID.randomUUID()
        val resume = document(DocumentSourceType.RESUME, resumeId, "Resume text")
        Mockito.`when`(resumes.findProcessingStatus(userId, resumeId)).thenReturn(Optional.of("READY"))
        Mockito.`when`(resumes.findReadyDocument(userId, resumeId)).thenReturn(Optional.of(resume))

        val result = resolver.resolveStrict(userId, resumeId, null)

        assertThat(result.resume()).isEqualTo(resume)
        assertThat(result.jobDescription()).isEmpty()
        Mockito.verify(resumes, Mockito.never()).findLatestReadyDocument(userId)
    }

    @Test
    fun strictResolutionResolvesJobDescriptionById() {
        val resumeId = UUID.randomUUID()
        val jobDescriptionId = UUID.randomUUID()
        val resume = document(DocumentSourceType.RESUME, resumeId, "Resume text")
        val jd = document(DocumentSourceType.JOB_DESCRIPTION, jobDescriptionId, "Job text")
        Mockito.`when`(resumes.findProcessingStatus(userId, resumeId)).thenReturn(Optional.of("READY"))
        Mockito.`when`(resumes.findReadyDocument(userId, resumeId)).thenReturn(Optional.of(resume))
        Mockito.`when`(jobDescriptions.findDocument(userId, jobDescriptionId)).thenReturn(Optional.of(jd))

        val result = resolver.resolveStrict(userId, resumeId, jobDescriptionId)

        assertThat(result.jobDescription()).contains(jd)
    }

    @Test
    fun strictResolutionRejectsNonReadyResumeWithoutFallback() {
        val resumeId = UUID.randomUUID()
        Mockito.`when`(resumes.findProcessingStatus(userId, resumeId)).thenReturn(Optional.of("PENDING"))

        assertThatThrownBy { resolver.resolveStrict(userId, resumeId, null) }
            .isInstanceOfSatisfying(ApiRequestException::class.java) { exception ->
                assertThat(exception.code()).isEqualTo("RESUME_NOT_READY")
            }
        Mockito.verify(resumes, Mockito.never()).findLatestReadyDocument(userId)
    }

    private fun document(sourceType: DocumentSourceType, id: UUID, text: String) =
        ResolvedDocument(sourceType, id, hasher.sha256(text), text, emptyList())
}
