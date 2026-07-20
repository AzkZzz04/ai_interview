package dev.jiaming.ai_interview.resume;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.jiaming.ai_interview.jobs.BackgroundJob;
import dev.jiaming.ai_interview.jobs.JobTerminalFailureHandler;
import dev.jiaming.ai_interview.jobs.JobType;

@Component
public class ResumeTerminalFailureHandler implements JobTerminalFailureHandler {

	private static final Logger log = LoggerFactory.getLogger(ResumeTerminalFailureHandler.class);

	private final ResumePersistenceService persistenceService;

	private final ResumeStorageService storageService;

	public ResumeTerminalFailureHandler(
		ResumePersistenceService persistenceService,
		ResumeStorageService storageService
	) {
		this.persistenceService = persistenceService;
		this.storageService = storageService;
	}

	@Override
	public boolean supports(JobType jobType) {
		return jobType == JobType.RESUME_EXTRACTION;
	}

	@Override
	public void handle(BackgroundJob job, String errorCode, String errorMessage) {
		if (job.resourceId() == null) {
			log.warn("resume_failure_missing_resource jobId={}", job.id());
			return;
		}
		Optional<String> storageKey = persistenceService.markFailed(
			job.resourceId(), errorCode, errorMessage
		);
		storageKey.ifPresent(key -> deleteAndClear(job, key));
	}

	private void deleteAndClear(BackgroundJob job, String storageKey) {
		storageService.delete(storageKey);
		persistenceService.clearStorageKey(job.resourceId(), storageKey);
		log.info("resume_failed_object_deleted jobId={} resumeId={}", job.id(), job.resourceId());
	}
}
