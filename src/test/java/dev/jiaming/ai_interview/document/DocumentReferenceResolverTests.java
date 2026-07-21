package dev.jiaming.ai_interview.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import dev.jiaming.ai_interview.common.ApiRequestException;
import dev.jiaming.ai_interview.common.ContentHasher;
import dev.jiaming.ai_interview.interview.JobDescriptionPersistenceService;
import dev.jiaming.ai_interview.resume.ResumePersistenceService;
import dev.jiaming.ai_interview.resume.ResumeTextNormalizer;

class DocumentReferenceResolverTests {

	private final ResumePersistenceService resumes = mock(ResumePersistenceService.class);

	private final JobDescriptionPersistenceService jobDescriptions = mock(JobDescriptionPersistenceService.class);

	private final ResumeTextNormalizer normalizer = new ResumeTextNormalizer();

	private final ContentHasher hasher = new ContentHasher();

	private final DocumentReferenceResolver resolver = new DocumentReferenceResolver(
		resumes,
		jobDescriptions,
		normalizer,
		hasher
	);

	private final UUID userId = UUID.randomUUID();

	@Test
	void resolvesUserScopedReadyResumeById() {
		UUID resumeId = UUID.randomUUID();
		ResolvedDocument resume = document(DocumentSourceType.RESUME, resumeId, "Resume text");
		when(resumes.findProcessingStatus(userId, resumeId)).thenReturn(Optional.of("READY"));
		when(resumes.findReadyDocument(userId, resumeId)).thenReturn(Optional.of(resume));

		ResolvedJobInputs result = resolver.resolveForSubmission(userId, resumeId, null, null, null);

		assertThat(result.resume()).isEqualTo(resume);
		assertThat(result.jobDescription()).isEmpty();
	}

	@Test
	void rejectsTextThatDoesNotMatchReferencedResume() {
		UUID resumeId = UUID.randomUUID();
		ResolvedDocument resume = document(DocumentSourceType.RESUME, resumeId, "Stored text");
		when(resumes.findProcessingStatus(userId, resumeId)).thenReturn(Optional.of("READY"));
		when(resumes.findReadyDocument(userId, resumeId)).thenReturn(Optional.of(resume));

		assertThatThrownBy(() -> resolver.resolveForSubmission(
			userId,
			resumeId,
			"Different text",
			null,
			null
		))
			.isInstanceOfSatisfying(ApiRequestException.class, exception ->
				assertThat(exception.code()).isEqualTo("REFERENCE_MISMATCH"));
	}

	@Test
	void legacyUpgradeMayResolveLatestResumeOnceToCreateAStableReference() {
		ResolvedDocument latest = document(DocumentSourceType.RESUME, UUID.randomUUID(), "Latest resume");
		when(resumes.findLatestReadyDocument(userId)).thenReturn(Optional.of(latest));

		assertThat(resolver.resolveForSubmission(userId, null, null, null, null).resume())
			.isEqualTo(latest);
		assertThat(resolver.resolveLegacy(userId, null, null, null, null).resume()).isEqualTo(latest);
	}

	@Test
	void materializesInlineDocumentsAndReturnsStableIds() {
		ResolvedDocument resume = document(DocumentSourceType.RESUME, UUID.randomUUID(), "Resume text");
		ResolvedDocument jd = document(DocumentSourceType.JOB_DESCRIPTION, UUID.randomUUID(), "Job text");
		when(resumes.findOrCreateDocument(userId, "Resume text")).thenReturn(resume);
		when(jobDescriptions.findOrCreateDocument(userId, "Job text")).thenReturn(jd);

		ResolvedJobInputs result = resolver.resolveForSubmission(
			userId,
			null,
			"Resume text",
			null,
			"Job text"
		);

		assertThat(result.resume().resourceId()).isEqualTo(resume.resourceId());
		assertThat(result.jobDescription()).contains(jd);
		verify(resumes).findOrCreateDocument(userId, "Resume text");
		verify(jobDescriptions).findOrCreateDocument(userId, "Job text");
	}

	@Test
	void rejectsPendingResumeWithoutFallingBack() {
		UUID resumeId = UUID.randomUUID();
		when(resumes.findProcessingStatus(userId, resumeId)).thenReturn(Optional.of("PENDING"));

		assertThatThrownBy(() -> resolver.resolveForSubmission(userId, resumeId, null, null, null))
			.isInstanceOfSatisfying(ApiRequestException.class, exception ->
				assertThat(exception.code()).isEqualTo("RESUME_NOT_READY"));
	}

	@Test
	void unknownResumeIdRaisesNotFound() {
		UUID resumeId = UUID.randomUUID();
		when(resumes.findProcessingStatus(userId, resumeId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> resolver.resolveForSubmission(userId, resumeId, null, null, null))
			.isInstanceOfSatisfying(ApiRequestException.class, exception -> {
				assertThat(exception.code()).isEqualTo("RESUME_NOT_FOUND");
				assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
			});
	}

	@Test
	void failedResumeIsRejectedWithoutFallingBack() {
		UUID resumeId = UUID.randomUUID();
		when(resumes.findProcessingStatus(userId, resumeId)).thenReturn(Optional.of("FAILED"));

		assertThatThrownBy(() -> resolver.resolveForSubmission(userId, resumeId, null, null, null))
			.isInstanceOfSatisfying(ApiRequestException.class, exception -> {
				assertThat(exception.code()).isEqualTo("RESUME_NOT_READY");
				assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
			});
	}

	@Test
	void readyStatusButMissingDocumentRaisesNotReady() {
		UUID resumeId = UUID.randomUUID();
		when(resumes.findProcessingStatus(userId, resumeId)).thenReturn(Optional.of("READY"));
		when(resumes.findReadyDocument(userId, resumeId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> resolver.resolveForSubmission(userId, resumeId, null, null, null))
			.isInstanceOfSatisfying(ApiRequestException.class, exception ->
				assertThat(exception.code()).isEqualTo("RESUME_NOT_READY"));
	}

	@Test
	void acceptsSuppliedTextThatMatchesReferencedResume() {
		UUID resumeId = UUID.randomUUID();
		String text = "Stored resume text";
		ResolvedDocument resume = document(DocumentSourceType.RESUME, resumeId, normalizer.normalize(text));
		when(resumes.findProcessingStatus(userId, resumeId)).thenReturn(Optional.of("READY"));
		when(resumes.findReadyDocument(userId, resumeId)).thenReturn(Optional.of(resume));

		ResolvedJobInputs result = resolver.resolveForSubmission(userId, resumeId, text, null, null);

		assertThat(result.resume()).isEqualTo(resume);
	}

	@Test
	void unknownJobDescriptionIdRaisesNotFound() {
		UUID jobDescriptionId = UUID.randomUUID();
		ResolvedDocument resume = document(DocumentSourceType.RESUME, UUID.randomUUID(), "Resume text");
		when(resumes.findOrCreateDocument(userId, "Resume text")).thenReturn(resume);
		when(jobDescriptions.findDocument(userId, jobDescriptionId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> resolver.resolveForSubmission(
			userId, null, "Resume text", jobDescriptionId, null
		))
			.isInstanceOfSatisfying(ApiRequestException.class, exception -> {
				assertThat(exception.code()).isEqualTo("JOB_DESCRIPTION_NOT_FOUND");
				assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
			});
	}

	@Test
	void rejectsTextThatDoesNotMatchReferencedJobDescription() {
		UUID jobDescriptionId = UUID.randomUUID();
		ResolvedDocument resume = document(DocumentSourceType.RESUME, UUID.randomUUID(), "Resume text");
		ResolvedDocument jd = document(DocumentSourceType.JOB_DESCRIPTION, jobDescriptionId, "Stored JD");
		when(resumes.findOrCreateDocument(userId, "Resume text")).thenReturn(resume);
		when(jobDescriptions.findDocument(userId, jobDescriptionId)).thenReturn(Optional.of(jd));

		assertThatThrownBy(() -> resolver.resolveForSubmission(
			userId, null, "Resume text", jobDescriptionId, "Different JD"
		))
			.isInstanceOfSatisfying(ApiRequestException.class, exception ->
				assertThat(exception.code()).isEqualTo("REFERENCE_MISMATCH"));
	}

	@Test
	void submissionWithNoResumeAndNoLatestRaisesTextRequired() {
		when(resumes.findLatestReadyDocument(userId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> resolver.resolveForSubmission(userId, null, "   ", null, null))
			.isInstanceOfSatisfying(ApiRequestException.class, exception -> {
				assertThat(exception.code()).isEqualTo("RESUME_TEXT_REQUIRED");
				assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
			});
	}

	@Test
	void preservesPersistedChunksOnResolvedResume() {
		UUID resumeId = UUID.randomUUID();
		List<DocumentChunk> chunks = List.of(new DocumentChunk(0, "Experience", "Built an API", "resume:experience:0"));
		ResolvedDocument resume = new ResolvedDocument(
			DocumentSourceType.RESUME, resumeId, hasher.sha256("Resume text"), "Resume text", chunks
		);
		when(resumes.findProcessingStatus(userId, resumeId)).thenReturn(Optional.of("READY"));
		when(resumes.findReadyDocument(userId, resumeId)).thenReturn(Optional.of(resume));

		ResolvedJobInputs result = resolver.resolveForSubmission(userId, resumeId, null, null, null);

		assertThat(result.resume().persistedChunks()).isEqualTo(chunks);
	}

	@Test
	void strictResolutionRequiresAResumeReference() {
		assertThatThrownBy(() -> resolver.resolveStrict(userId, null, null))
			.isInstanceOfSatisfying(ApiRequestException.class, exception -> {
				assertThat(exception.code()).isEqualTo("RESUME_REFERENCE_REQUIRED");
				assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
			});
	}

	@Test
	void strictResolutionLoadsByIdAndNeverFallsBackToLatest() {
		UUID resumeId = UUID.randomUUID();
		ResolvedDocument resume = document(DocumentSourceType.RESUME, resumeId, "Resume text");
		when(resumes.findProcessingStatus(userId, resumeId)).thenReturn(Optional.of("READY"));
		when(resumes.findReadyDocument(userId, resumeId)).thenReturn(Optional.of(resume));

		ResolvedJobInputs result = resolver.resolveStrict(userId, resumeId, null);

		assertThat(result.resume()).isEqualTo(resume);
		assertThat(result.jobDescription()).isEmpty();
		verify(resumes, never()).findLatestReadyDocument(userId);
	}

	@Test
	void strictResolutionResolvesJobDescriptionById() {
		UUID resumeId = UUID.randomUUID();
		UUID jobDescriptionId = UUID.randomUUID();
		ResolvedDocument resume = document(DocumentSourceType.RESUME, resumeId, "Resume text");
		ResolvedDocument jd = document(DocumentSourceType.JOB_DESCRIPTION, jobDescriptionId, "Job text");
		when(resumes.findProcessingStatus(userId, resumeId)).thenReturn(Optional.of("READY"));
		when(resumes.findReadyDocument(userId, resumeId)).thenReturn(Optional.of(resume));
		when(jobDescriptions.findDocument(userId, jobDescriptionId)).thenReturn(Optional.of(jd));

		ResolvedJobInputs result = resolver.resolveStrict(userId, resumeId, jobDescriptionId);

		assertThat(result.jobDescription()).contains(jd);
	}

	@Test
	void strictResolutionRejectsNonReadyResumeWithoutFallback() {
		UUID resumeId = UUID.randomUUID();
		when(resumes.findProcessingStatus(userId, resumeId)).thenReturn(Optional.of("PENDING"));

		assertThatThrownBy(() -> resolver.resolveStrict(userId, resumeId, null))
			.isInstanceOfSatisfying(ApiRequestException.class, exception ->
				assertThat(exception.code()).isEqualTo("RESUME_NOT_READY"));
		verify(resumes, never()).findLatestReadyDocument(userId);
	}

	private ResolvedDocument document(DocumentSourceType sourceType, UUID id, String text) {
		return new ResolvedDocument(sourceType, id, hasher.sha256(text), text, List.of());
	}
}
