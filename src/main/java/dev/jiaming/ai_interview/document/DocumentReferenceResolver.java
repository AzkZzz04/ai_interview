package dev.jiaming.ai_interview.document;

import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.jiaming.ai_interview.common.ApiRequestException;
import dev.jiaming.ai_interview.common.ContentHasher;
import dev.jiaming.ai_interview.interview.JobDescriptionPersistenceService;
import dev.jiaming.ai_interview.resume.ResumePersistenceService;
import dev.jiaming.ai_interview.resume.ResumeTextNormalizer;

@Service
public class DocumentReferenceResolver {

	private final ResumePersistenceService resumePersistenceService;

	private final JobDescriptionPersistenceService jobDescriptionPersistenceService;

	private final ResumeTextNormalizer normalizer;

	private final ContentHasher contentHasher;

	public DocumentReferenceResolver(
		ResumePersistenceService resumePersistenceService,
		JobDescriptionPersistenceService jobDescriptionPersistenceService,
		ResumeTextNormalizer normalizer,
		ContentHasher contentHasher
	) {
		this.resumePersistenceService = resumePersistenceService;
		this.jobDescriptionPersistenceService = jobDescriptionPersistenceService;
		this.normalizer = normalizer;
		this.contentHasher = contentHasher;
	}

	@Transactional
	public ResolvedJobInputs resolveForSubmission(
		UUID userId,
		UUID resumeId,
		String resumeText,
		UUID jobDescriptionId,
		String jobDescription
	) {
		ResolvedDocument resume = resolveResume(userId, resumeId, resumeText, true);
		Optional<ResolvedDocument> resolvedJobDescription = resolveJobDescription(
			userId,
			jobDescriptionId,
			jobDescription
		);
		return new ResolvedJobInputs(resume, resolvedJobDescription);
	}

	@Transactional
	public ResolvedJobInputs resolveLegacy(
		UUID userId,
		UUID resumeId,
		String resumeText,
		UUID jobDescriptionId,
		String jobDescription
	) {
		ResolvedDocument resume = resolveResume(userId, resumeId, resumeText, true);
		return new ResolvedJobInputs(
			resume,
			resolveJobDescription(userId, jobDescriptionId, jobDescription)
		);
	}

	@Transactional(readOnly = true)
	public ResolvedJobInputs resolveStrict(
		UUID userId,
		UUID resumeId,
		UUID jobDescriptionId
	) {
		if (resumeId == null) {
			throw new ApiRequestException(
				HttpStatus.BAD_REQUEST,
				"RESUME_REFERENCE_REQUIRED",
				"The background job does not contain a resume reference"
			);
		}
		ResolvedDocument resume = resolveResume(userId, resumeId, null, false);
		Optional<ResolvedDocument> jobDescription = jobDescriptionId == null
			? Optional.empty()
			: Optional.of(resolveJobDescription(userId, jobDescriptionId, null).orElseThrow());
		return new ResolvedJobInputs(resume, jobDescription);
	}

	private ResolvedDocument resolveResume(
		UUID userId,
		UUID resumeId,
		String resumeText,
		boolean allowLatest
	) {
		if (resumeId != null) {
			String status = resumePersistenceService.findProcessingStatus(userId, resumeId)
				.orElseThrow(() -> new ApiRequestException(
					HttpStatus.NOT_FOUND,
					"RESUME_NOT_FOUND",
					"Resume was not found"
				));
			if (!"READY".equals(status)) {
				throw new ApiRequestException(
					HttpStatus.CONFLICT,
					"RESUME_NOT_READY",
					"Resume is not ready for analysis"
				);
			}
			ResolvedDocument document = resumePersistenceService.findReadyDocument(userId, resumeId)
				.orElseThrow(() -> new ApiRequestException(
					HttpStatus.CONFLICT,
					"RESUME_NOT_READY",
					"Resume is not ready for analysis"
				));
			assertMatchingText(document, resumeText);
			return document;
		}

		String normalizedText = normalizer.normalize(resumeText);
		if (!normalizedText.isBlank()) {
			return resumePersistenceService.findOrCreateDocument(userId, resumeText);
		}
		if (allowLatest) {
			return resumePersistenceService.findLatestReadyDocument(userId)
				.orElseThrow(this::missingResume);
		}
		throw missingResume();
	}

	private Optional<ResolvedDocument> resolveJobDescription(
		UUID userId,
		UUID jobDescriptionId,
		String jobDescription
	) {
		if (jobDescriptionId != null) {
			ResolvedDocument document = jobDescriptionPersistenceService.findDocument(userId, jobDescriptionId)
				.orElseThrow(() -> new ApiRequestException(
					HttpStatus.NOT_FOUND,
					"JOB_DESCRIPTION_NOT_FOUND",
					"Job description was not found"
				));
			assertMatchingText(document, jobDescription);
			return Optional.of(document);
		}

		String normalizedText = normalizer.normalize(jobDescription);
		if (normalizedText.isBlank()) {
			return Optional.empty();
		}
		return Optional.of(jobDescriptionPersistenceService.findOrCreateDocument(userId, jobDescription));
	}

	private void assertMatchingText(ResolvedDocument document, String suppliedText) {
		if (suppliedText == null || suppliedText.isBlank()) {
			return;
		}
		String normalizedText = normalizer.normalize(suppliedText);
		String suppliedHash = contentHasher.sha256(normalizedText);
		if (!document.contentHash().equals(suppliedHash)
			|| !document.normalizedText().equals(normalizedText)) {
			throw new ApiRequestException(
				HttpStatus.CONFLICT,
				"REFERENCE_MISMATCH",
				"The supplied text does not match the referenced document"
			);
		}
	}

	private ApiRequestException missingResume() {
		return new ApiRequestException(
			HttpStatus.BAD_REQUEST,
			"RESUME_TEXT_REQUIRED",
			"Resume text is required. Paste text or upload a resume first."
		);
	}
}
