package dev.jiaming.ai_interview.resume

import java.util.UUID
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class ResumeStorageReconcilerTests {
	private val persistenceService = Mockito.mock(ResumePersistenceService::class.java)
	private val storageService = Mockito.mock(ResumeStorageService::class.java)
	private val cleanupService = Mockito.mock(ResumeStorageCleanupService::class.java)
	private val reconciler = ResumeStorageReconciler(persistenceService, storageService, cleanupService)

	@Test
	fun retriesDeletionAndClearsKeyOnlyAfterSuccess() {
		val failed = FailedResumeStorage(UUID.randomUUID(), "resumes/key")
		Mockito.`when`(persistenceService.findUnappliedTerminalFailures(25)).thenReturn(emptyList())
		Mockito.`when`(persistenceService.findFailedStorageObjects(25)).thenReturn(listOf(failed))
		Mockito.`when`(cleanupService.retryPending(25)).thenReturn(0)

		reconciler.deleteFailedResumeObjects()

		Mockito.verify(storageService).delete(failed.storageKey)
		Mockito.verify(persistenceService).clearStorageKey(failed.resumeId, failed.storageKey)
	}

	@Test
	fun failedDeletionLeavesKeyForNextRun() {
		val failed = FailedResumeStorage(UUID.randomUUID(), "resumes/key")
		Mockito.`when`(persistenceService.findUnappliedTerminalFailures(25)).thenReturn(emptyList())
		Mockito.`when`(persistenceService.findFailedStorageObjects(25)).thenReturn(listOf(failed))
		Mockito.`when`(cleanupService.retryPending(25)).thenReturn(0)
		Mockito.doThrow(IllegalStateException("offline")).`when`(storageService).delete(failed.storageKey)

		reconciler.deleteFailedResumeObjects()

		Mockito.verify(persistenceService, Mockito.never()).clearStorageKey(failed.resumeId, failed.storageKey)
	}

	@Test
	fun appliesTerminalJobFailureEvenWhenWorkerCleanupWasInterrupted() {
		val failedJob = FailedResumeJob(UUID.randomUUID(), "RESUME_EXTRACTION_FAILED", "Malformed PDF")
		Mockito.`when`(persistenceService.findUnappliedTerminalFailures(25)).thenReturn(listOf(failedJob))
		Mockito.`when`(persistenceService.findFailedStorageObjects(25)).thenReturn(emptyList())
		Mockito.`when`(cleanupService.retryPending(25)).thenReturn(0)

		reconciler.deleteFailedResumeObjects()

		Mockito.verify(persistenceService).markFailed(failedJob.resumeId, failedJob.errorCode, failedJob.errorMessage)
	}

	@Test
	fun retriesDurableCleanupQueueAlongsideFailedResumeFallback() {
		Mockito.`when`(persistenceService.findUnappliedTerminalFailures(25)).thenReturn(emptyList())
		Mockito.`when`(persistenceService.findFailedStorageObjects(25)).thenReturn(emptyList())
		Mockito.`when`(cleanupService.retryPending(25)).thenReturn(1)

		reconciler.deleteFailedResumeObjects()

		Mockito.verify(cleanupService).retryPending(25)
	}
}
