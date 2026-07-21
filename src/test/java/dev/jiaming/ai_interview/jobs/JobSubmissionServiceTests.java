package dev.jiaming.ai_interview.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import dev.jiaming.ai_interview.coach.AiAnalysisRequest;
import dev.jiaming.ai_interview.common.RuntimeModeProperties;
import dev.jiaming.ai_interview.common.LocalUserService;
import dev.jiaming.ai_interview.common.RedisRequestGuard;
import dev.jiaming.ai_interview.document.DocumentReferenceResolver;
import dev.jiaming.ai_interview.document.DocumentSourceType;
import dev.jiaming.ai_interview.document.ResolvedDocument;
import dev.jiaming.ai_interview.document.ResolvedJobInputs;

class JobSubmissionServiceTests {

	private final BackgroundJobStore jobStore = mock(BackgroundJobStore.class);

	private final JobDispatcher dispatcher = mock(JobDispatcher.class);

	private final LocalUserService localUserService = mock(LocalUserService.class);

	private final JobMetrics metrics = mock(JobMetrics.class);

	private final RedisRequestGuard requestGuard = mock(RedisRequestGuard.class);

	private final DocumentReferenceResolver documentResolver = mock(DocumentReferenceResolver.class);

	private final UUID userId = UUID.randomUUID();

	private final JobProperties properties = new JobProperties(
		true, "http://localhost:4566", "us-east-1", "test", "test", "jobs", "jobs-dlq", 3,
		2, 20, 300, 60, 3, 15, 300, 5_000, 30_000, 3_600_000, 120, 7
	);

	private final JobSubmissionService service = new JobSubmissionService(
		jobStore,
		dispatcher,
		new RequestFingerprintService(new ObjectMapper()),
		localUserService,
		requestGuard,
		documentResolver,
		properties,
		new RuntimeModeProperties("all"),
		metrics,
		new ObjectMapper()
	);

	@BeforeEach
	void passThroughIdempotencyGuard() {
		doAnswer(invocation -> invocation.<Supplier<?>>getArgument(3).get())
			.when(requestGuard).withIdempotentRetryCache(any(), any(), any(), any());
	}

	@Test
	void reusesMatchingJobWithinFiveMinuteWindow() {
		BackgroundJob existing = job(JobStatus.SUCCEEDED);
		when(localUserService.localUserId()).thenReturn(userId);
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
		when(jobStore.findReusable(userId, JobType.ANALYSIS, "new", Duration.ofMinutes(5)))
			.thenReturn(Optional.empty());
		when(jobStore.createIfAbsent(eq(userId), eq(JobType.ANALYSIS), eq("resume"), eq(null),
			org.mockito.ArgumentMatchers.any(), eq("new"), eq(3))).thenReturn(Optional.of(created));

		JobAcceptedResponse response = service.createOrReuse(
			JobType.ANALYSIS, "resume", null, java.util.Map.of("resume", "new"), "new"
		);

		assertThat(response.reused()).isFalse();
		verify(dispatcher).dispatch(created.id());
		verify(metrics).submitted(JobType.ANALYSIS);
	}

	@Test
	void reusesWinningJobWhenConcurrentInsertDoesNotCreateARow() {
		BackgroundJob winner = job(JobStatus.QUEUED);
		when(localUserService.localUserId()).thenReturn(userId);
		when(jobStore.findReusable(userId, JobType.ANALYSIS, "race", Duration.ofMinutes(5)))
			.thenReturn(Optional.empty(), Optional.of(winner));
		when(jobStore.createIfAbsent(
			eq(userId),
			eq(JobType.ANALYSIS),
			eq("resume"),
			eq(null),
			any(),
			eq("race"),
			eq(3)
		)).thenReturn(Optional.empty());

		JobAcceptedResponse response = service.createOrReuse(
			JobType.ANALYSIS, "resume", null, java.util.Map.of("resume", "same"), "race"
		);

		assertThat(response.reused()).isTrue();
		assertThat(response.jobId()).isEqualTo(winner.id());
		verifyNoInteractions(dispatcher);
	}

	@Test
	void analysisJobPayloadContainsReferencesButNotResumeOrJobDescriptionText() {
		String resumeMarker = "PII_RESUME_MARKER_91F4";
		String jobMarker = "PII_JOB_MARKER_A23C";
		UUID resumeId = UUID.randomUUID();
		UUID jobDescriptionId = UUID.randomUUID();
		ResolvedJobInputs resolved = resolved(resumeId, jobDescriptionId, resumeMarker, jobMarker);
		AiAnalysisRequest request = new AiAnalysisRequest(
			null, resumeMarker, null, jobMarker, "Backend Engineer", "Mid-level"
		);
		BackgroundJob created = job(JobStatus.QUEUED);
		when(localUserService.localUserId()).thenReturn(userId);
		when(documentResolver.resolveForSubmission(userId, null, resumeMarker, null, jobMarker))
			.thenReturn(resolved);
		when(jobStore.findReusable(eq(userId), eq(JobType.ANALYSIS), any(), eq(Duration.ofMinutes(5))))
			.thenReturn(Optional.empty());
		when(jobStore.createIfAbsent(eq(userId), eq(JobType.ANALYSIS), eq("resume"), eq(resumeId), any(), any(), eq(3)))
			.thenReturn(Optional.of(created));

		service.submitAnalysis(request);

		ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> payload =
			ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
		verify(jobStore).createIfAbsent(
			eq(userId), eq(JobType.ANALYSIS), eq("resume"), eq(resumeId), payload.capture(), any(), eq(3)
		);
		assertThat(payload.getValue().path("payloadVersion").asInt()).isEqualTo(2);
		assertThat(payload.getValue().path("resumeId").asText()).isEqualTo(resumeId.toString());
		assertThat(payload.getValue().path("jobDescriptionId").asText()).isEqualTo(jobDescriptionId.toString());
		assertThat(payload.getValue().toString()).doesNotContain(resumeMarker, jobMarker);
	}

	@Test
	void idempotencyResponseIsStoredOnlyAfterTheSubmissionTransactionCompletes() {
		List<String> events = new ArrayList<>();
		TransactionOperations transactions = new TransactionOperations() {
			@Override
			public <T> T execute(TransactionCallback<T> action) {
				events.add("transaction-started");
				T result = action.doInTransaction(mock(TransactionStatus.class));
				events.add("transaction-committed");
				return result;
			}
		};
		JobSubmissionService transactionalService = new JobSubmissionService(
			jobStore,
			dispatcher,
			new RequestFingerprintService(new ObjectMapper()),
			localUserService,
			requestGuard,
			documentResolver,
			properties,
			new RuntimeModeProperties("all"),
			metrics,
			new ObjectMapper(),
			transactions
		);
		UUID resumeId = UUID.randomUUID();
		ResolvedJobInputs resolved = new ResolvedJobInputs(
			new ResolvedDocument(DocumentSourceType.RESUME, resumeId, "resume-hash", "resume", List.of()),
			Optional.empty()
		);
		AiAnalysisRequest request = new AiAnalysisRequest(
			resumeId, null, null, null, "Backend Engineer", "Mid-level"
		);
		BackgroundJob created = job(JobStatus.QUEUED);
		when(localUserService.localUserId()).thenReturn(userId);
		when(documentResolver.resolveForSubmission(userId, resumeId, null, null, null)).thenReturn(resolved);
		when(jobStore.findReusable(eq(userId), eq(JobType.ANALYSIS), any(), eq(Duration.ofMinutes(5))))
			.thenReturn(Optional.empty());
		when(jobStore.createIfAbsent(eq(userId), eq(JobType.ANALYSIS), eq("resume"), eq(resumeId), any(), any(), eq(3)))
			.thenReturn(Optional.of(created));
		doAnswer(invocation -> {
			events.add("idempotency-started");
			Object response = invocation.<Supplier<?>>getArgument(3).get();
			events.add("idempotency-response-stored");
			return response;
		}).when(requestGuard).withIdempotentRetryCache(any(), any(), any(), any());

		transactionalService.submitAnalysis(request);

		assertThat(events).containsExactly(
			"idempotency-started",
			"transaction-started",
			"transaction-committed",
			"idempotency-response-stored"
		);
	}

	private ResolvedJobInputs resolved(
		UUID resumeId,
		UUID jobDescriptionId,
		String resumeText,
		String jobDescriptionText
	) {
		return new ResolvedJobInputs(
			new ResolvedDocument(DocumentSourceType.RESUME, resumeId, "same-resume-hash", resumeText, List.of()),
			Optional.of(new ResolvedDocument(
				DocumentSourceType.JOB_DESCRIPTION,
				jobDescriptionId,
				"same-job-hash",
				jobDescriptionText,
				List.of()
			))
		);
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
