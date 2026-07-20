package dev.jiaming.ai_interview.resume;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class ResumeStorageReconciler {

	private static final Logger log = LoggerFactory.getLogger(ResumeStorageReconciler.class);

	private final ResumePersistenceService persistenceService;

	private final ResumeStorageService storageService;

	ResumeStorageReconciler(
		ResumePersistenceService persistenceService,
		ResumeStorageService storageService
	) {
		this.persistenceService = persistenceService;
		this.storageService = storageService;
	}

	@Scheduled(fixedDelayString = "${app.storage.cleanup-interval-ms:300000}")
	void deleteFailedResumeObjects() {
		for (FailedResumeJob failedJob : persistenceService.findUnappliedTerminalFailures(25)) {
			try {
				persistenceService.markFailed(
					failedJob.resumeId(), failedJob.errorCode(), failedJob.errorMessage()
				);
			}
			catch (RuntimeException exception) {
				log.warn("resume_terminal_failure_reconcile_failed resumeId={} reason={}",
					failedJob.resumeId(), exception.getMessage());
			}
		}
		for (FailedResumeStorage failed : persistenceService.findFailedStorageObjects(25)) {
			try {
				storageService.delete(failed.storageKey());
				persistenceService.clearStorageKey(failed.resumeId(), failed.storageKey());
				log.info("resume_failed_object_reconciled resumeId={}", failed.resumeId());
			}
			catch (RuntimeException exception) {
				log.warn("resume_failed_object_reconcile_failed resumeId={} reason={}",
					failed.resumeId(), exception.getMessage());
			}
		}
	}
}
