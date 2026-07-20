package dev.jiaming.ai_interview.resume;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ResumeStorageReconcilerTests {

	private final ResumePersistenceService persistenceService = mock(ResumePersistenceService.class);

	private final ResumeStorageService storageService = mock(ResumeStorageService.class);

	private final ResumeStorageReconciler reconciler = new ResumeStorageReconciler(
		persistenceService, storageService
	);

	@Test
	void retriesDeletionAndClearsKeyOnlyAfterSuccess() {
		FailedResumeStorage failed = new FailedResumeStorage(UUID.randomUUID(), "resumes/key");
		when(persistenceService.findUnappliedTerminalFailures(25)).thenReturn(List.of());
		when(persistenceService.findFailedStorageObjects(25)).thenReturn(List.of(failed));

		reconciler.deleteFailedResumeObjects();

		verify(storageService).delete(failed.storageKey());
		verify(persistenceService).clearStorageKey(failed.resumeId(), failed.storageKey());
	}

	@Test
	void failedDeletionLeavesKeyForNextRun() {
		FailedResumeStorage failed = new FailedResumeStorage(UUID.randomUUID(), "resumes/key");
		when(persistenceService.findUnappliedTerminalFailures(25)).thenReturn(List.of());
		when(persistenceService.findFailedStorageObjects(25)).thenReturn(List.of(failed));
		org.mockito.Mockito.doThrow(new IllegalStateException("offline"))
			.when(storageService).delete(failed.storageKey());

		reconciler.deleteFailedResumeObjects();

		verify(persistenceService, never()).clearStorageKey(failed.resumeId(), failed.storageKey());
	}

	@Test
	void appliesTerminalJobFailureEvenWhenWorkerCleanupWasInterrupted() {
		FailedResumeJob failedJob = new FailedResumeJob(
			UUID.randomUUID(), "RESUME_EXTRACTION_FAILED", "Malformed PDF"
		);
		when(persistenceService.findUnappliedTerminalFailures(25)).thenReturn(List.of(failedJob));
		when(persistenceService.findFailedStorageObjects(25)).thenReturn(List.of());

		reconciler.deleteFailedResumeObjects();

		verify(persistenceService).markFailed(
			failedJob.resumeId(), failedJob.errorCode(), failedJob.errorMessage()
		);
	}
}
