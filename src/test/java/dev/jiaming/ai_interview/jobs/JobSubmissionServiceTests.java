package dev.jiaming.ai_interview.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import dev.jiaming.ai_interview.coach.CoachResumeResolver;
import dev.jiaming.ai_interview.common.LocalUserService;
import dev.jiaming.ai_interview.common.RedisRequestGuard;

class JobSubmissionServiceTests {

	private final BackgroundJobStore jobStore = mock(BackgroundJobStore.class);

	private final JobDispatcher dispatcher = mock(JobDispatcher.class);

	private final JobResultCache resultCache = mock(JobResultCache.class);

	private final LocalUserService localUserService = mock(LocalUserService.class);

	private final JobMetrics metrics = mock(JobMetrics.class);

	private final UUID userId = UUID.randomUUID();

	private final JobProperties properties = new JobProperties(
		true, "all", "http://localhost:4566", "us-east-1", "test", "test", "jobs", "jobs-dlq", 3,
		2, 20, 300, 60, 3, 15, 300, 5_000, 30_000, 3_600_000, 120, 7
	);

	private final JobSubmissionService service = new JobSubmissionService(
		jobStore,
		dispatcher,
		resultCache,
		new RequestFingerprintService(new ObjectMapper()),
		localUserService,
		mock(RedisRequestGuard.class),
		mock(CoachResumeResolver.class),
		properties,
		metrics,
		new ObjectMapper()
	);

	@Test
	void reusesMatchingJobWithinFiveMinuteWindow() {
		BackgroundJob existing = job(JobStatus.SUCCEEDED);
		when(localUserService.localUserId()).thenReturn(userId);
		when(resultCache.find(userId, JobType.ANALYSIS, "same")).thenReturn(Optional.empty());
		when(jobStore.findReusable(userId, JobType.ANALYSIS, "same", Duration.ofMinutes(5)))
			.thenReturn(Optional.of(existing));

		JobAcceptedResponse response = service.createOrReuse(
			JobType.ANALYSIS, "resume", null, java.util.Map.of("resume", "same"), "same"
		);

		assertThat(response.reused()).isTrue();
		assertThat(response.jobId()).isEqualTo(existing.id());
		verifyNoInteractions(dispatcher);
	}

	@Test
	void createsAndDispatchesNewJobWhenNoReusableJobExists() {
		BackgroundJob created = job(JobStatus.QUEUED);
		when(localUserService.localUserId()).thenReturn(userId);
		when(resultCache.find(userId, JobType.ANALYSIS, "new")).thenReturn(Optional.empty());
		when(jobStore.findReusable(userId, JobType.ANALYSIS, "new", Duration.ofMinutes(5)))
			.thenReturn(Optional.empty());
		when(jobStore.create(eq(userId), eq(JobType.ANALYSIS), eq("resume"), eq(null),
			org.mockito.ArgumentMatchers.any(), eq("new"), eq(3))).thenReturn(created);

		JobAcceptedResponse response = service.createOrReuse(
			JobType.ANALYSIS, "resume", null, java.util.Map.of("resume", "new"), "new"
		);

		assertThat(response.reused()).isFalse();
		verify(dispatcher).dispatch(created.id());
		verify(metrics).submitted(JobType.ANALYSIS);
	}

	private BackgroundJob job(JobStatus status) {
		Instant now = Instant.now();
		return new BackgroundJob(
			UUID.randomUUID(), userId, JobType.ANALYSIS, "resume", null, status, JobStage.QUEUED,
			new ObjectMapper().createObjectNode(), null, status == JobStatus.QUEUED ? "new" : "same", 0, 3,
			null, null, null, now, now, now, status == JobStatus.QUEUED ? null : now, null,
			status == JobStatus.SUCCEEDED ? now : null, null, null
		);
	}
}
