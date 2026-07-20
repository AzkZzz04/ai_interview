package dev.jiaming.ai_interview.resume;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import dev.jiaming.ai_interview.jobs.BackgroundJob;
import dev.jiaming.ai_interview.jobs.JobStage;
import dev.jiaming.ai_interview.jobs.JobStatus;
import dev.jiaming.ai_interview.jobs.JobType;

class ResumeTerminalFailureHandlerTests {

	private final ResumePersistenceService persistenceService = mock(ResumePersistenceService.class);

	private final ResumeStorageService storageService = mock(ResumeStorageService.class);

	private final ResumeTerminalFailureHandler handler = new ResumeTerminalFailureHandler(
		persistenceService, storageService
	);

	@Test
	void marksResumeFailedDeletesObjectAndClearsStorageKey() {
		BackgroundJob job = job();
		when(persistenceService.markFailed(job.resourceId(), "RESUME_EXTRACTION_FAILED", "Encrypted PDF"))
			.thenReturn(Optional.of("resumes/key"));

		handler.handle(job, "RESUME_EXTRACTION_FAILED", "Encrypted PDF");

		verify(storageService).delete("resumes/key");
		verify(persistenceService).clearStorageKey(job.resourceId(), "resumes/key");
	}

	@Test
	void retainsStorageKeyWhenDeletionFailsForScheduledReconciliation() {
		BackgroundJob job = job();
		when(persistenceService.markFailed(job.resourceId(), "RESUME_EXTRACTION_FAILED", "Malformed PDF"))
			.thenReturn(Optional.of("resumes/key"));
		org.mockito.Mockito.doThrow(new IllegalStateException("S3 unavailable"))
			.when(storageService).delete("resumes/key");

		assertThatThrownBy(() -> handler.handle(job, "RESUME_EXTRACTION_FAILED", "Malformed PDF"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("S3 unavailable");

		verify(persistenceService, never()).clearStorageKey(job.resourceId(), "resumes/key");
	}

	private BackgroundJob job() {
		Instant now = Instant.now();
		return new BackgroundJob(
			UUID.randomUUID(), UUID.randomUUID(), JobType.RESUME_EXTRACTION, "resume", UUID.randomUUID(),
			JobStatus.PROCESSING, JobStage.EXTRACTING_TEXT, new ObjectMapper().createObjectNode(), null,
			"fingerprint", 1, 3, null, null, null, now, now, now, now, now,
			null, UUID.randomUUID(), now.plusSeconds(300)
		);
	}
}
