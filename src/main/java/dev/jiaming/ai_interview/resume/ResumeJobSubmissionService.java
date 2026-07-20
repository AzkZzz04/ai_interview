package dev.jiaming.ai_interview.resume;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import dev.jiaming.ai_interview.common.RedisRequestGuard;
import dev.jiaming.ai_interview.jobs.JobAcceptedResponse;
import dev.jiaming.ai_interview.jobs.JobSubmissionService;
import dev.jiaming.ai_interview.jobs.JobType;

@Service
public class ResumeJobSubmissionService {

	private static final Logger log = LoggerFactory.getLogger(ResumeJobSubmissionService.class);

	private final ResumeFileValidator validator;

	private final ResumeFileReader fileReader;

	private final ResumeStorageService storageService;

	private final ResumePersistenceService persistenceService;

	private final JobSubmissionService jobSubmissionService;

	private final RedisRequestGuard requestGuard;

	public ResumeJobSubmissionService(
		ResumeFileValidator validator,
		ResumeFileReader fileReader,
		ResumeStorageService storageService,
		ResumePersistenceService persistenceService,
		JobSubmissionService jobSubmissionService,
		RedisRequestGuard requestGuard
	) {
		this.validator = validator;
		this.fileReader = fileReader;
		this.storageService = storageService;
		this.persistenceService = persistenceService;
		this.jobSubmissionService = jobSubmissionService;
		this.requestGuard = requestGuard;
	}

	public JobAcceptedResponse submit(MultipartFile file) {
		jobSubmissionService.assertApiAvailable();
		validator.validate(file);
		ResumeFileContent content = fileReader.read(file);
		UploadFingerprint source = new UploadFingerprint(
			content.originalFilename(),
			content.contentType(),
			content.detectedContentType(),
			content.sizeBytes(),
			sha256(content.bytes())
		);
		String fingerprint = jobSubmissionService.fingerprint("resume-upload", source);
		return jobSubmissionService.withIdempotency(
			"resume-upload",
			source,
			JobAcceptedResponse.class,
			() -> submitNewOrReuse(content, fingerprint)
		);
	}

	private JobAcceptedResponse submitNewOrReuse(ResumeFileContent content, String fingerprint) {
		requestGuard.assertUploadAllowed();
		var existing = jobSubmissionService.findReusable(JobType.RESUME_EXTRACTION, fingerprint);
		if (existing.isPresent()) {
			return existing.get();
		}

		String storageKey = null;
		UUID resumeId = null;
		try {
			storageKey = storageService.store(content);
			resumeId = persistenceService.createPending(
				content.originalFilename(),
				content.contentType(),
				content.detectedContentType(),
				content.sizeBytes(),
				storageKey
			);
			ResumeExtractionJobPayload payload = new ResumeExtractionJobPayload(
				resumeId,
				storageKey,
				content.originalFilename(),
				content.contentType(),
				content.detectedContentType(),
				content.sizeBytes(),
				content.extension()
			);
			JobAcceptedResponse response = jobSubmissionService.createOrReuse(
				JobType.RESUME_EXTRACTION,
				"resume",
				resumeId,
				payload,
				fingerprint
			);
			if (response.reused()) {
				compensate(resumeId, storageKey);
			}
			return response;
		}
		catch (RuntimeException exception) {
			compensate(resumeId, storageKey);
			throw exception;
		}
	}

	private void compensate(UUID resumeId, String storageKey) {
		if (resumeId != null) {
			try {
				persistenceService.deletePending(resumeId);
			}
			catch (RuntimeException exception) {
				log.warn("resume_upload_compensation_db_failed resumeId={} reason={}", resumeId, exception.getMessage());
			}
		}
		if (storageKey != null) {
			try {
				storageService.delete(storageKey);
			}
			catch (RuntimeException exception) {
				log.warn("resume_upload_compensation_storage_failed storageKey={} reason={}",
					storageKey, exception.getMessage());
			}
		}
	}

	private String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private record UploadFingerprint(
		String originalFilename,
		String contentType,
		String detectedContentType,
		long sizeBytes,
		String contentHash
	) {
	}
}
