package dev.jiaming.ai_interview.resume

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.jobs.BackgroundJob
import dev.jiaming.ai_interview.jobs.JobStage
import dev.jiaming.ai_interview.jobs.JobStatus
import dev.jiaming.ai_interview.jobs.JobType
import java.time.Instant
import java.util.Optional
import java.util.UUID
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class ResumeTerminalFailureHandlerTests {
	private val persistenceService = Mockito.mock(ResumePersistenceService::class.java)
	private val storageService = Mockito.mock(ResumeStorageService::class.java)
	private val handler = ResumeTerminalFailureHandler(persistenceService, storageService)

	@Test
	fun marksResumeFailedDeletesObjectAndClearsStorageKey() {
		val job = job()
		Mockito.`when`(persistenceService.markFailed(job.resourceId(), "RESUME_EXTRACTION_FAILED", "Encrypted PDF")).thenReturn(Optional.of("resumes/key"))
		handler.handle(job, "RESUME_EXTRACTION_FAILED", "Encrypted PDF")
		Mockito.verify(storageService).delete("resumes/key")
		Mockito.verify(persistenceService).clearStorageKey(job.resourceId(), "resumes/key")
	}

	@Test
	fun retainsStorageKeyWhenDeletionFailsForScheduledReconciliation() {
		val job = job()
		Mockito.`when`(persistenceService.markFailed(job.resourceId(), "RESUME_EXTRACTION_FAILED", "Malformed PDF")).thenReturn(Optional.of("resumes/key"))
		Mockito.doThrow(IllegalStateException("S3 unavailable")).`when`(storageService).delete("resumes/key")
		assertThatThrownBy { handler.handle(job, "RESUME_EXTRACTION_FAILED", "Malformed PDF") }
			.isInstanceOf(IllegalStateException::class.java)
			.hasMessageContaining("S3 unavailable")
		Mockito.verify(persistenceService, Mockito.never()).clearStorageKey(job.resourceId(), "resumes/key")
	}

	private fun job(): BackgroundJob {
		val now = Instant.now()
		return BackgroundJob(UUID.randomUUID(), UUID.randomUUID(), JobType.RESUME_EXTRACTION, "resume", UUID.randomUUID(), JobStatus.PROCESSING, JobStage.EXTRACTING_TEXT, ObjectMapper().createObjectNode(), null, "fingerprint", 1, 3, null, null, null, now, now, now, now, now, null, UUID.randomUUID(), now.plusSeconds(300))
	}
}
