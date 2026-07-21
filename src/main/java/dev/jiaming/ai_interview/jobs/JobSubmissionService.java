package dev.jiaming.ai_interview.jobs;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.server.ResponseStatusException;

import dev.jiaming.ai_interview.coach.AiAnalysisRequest;
import dev.jiaming.ai_interview.coach.AnswerFeedbackRequest;
import dev.jiaming.ai_interview.common.LocalUserService;
import dev.jiaming.ai_interview.common.RedisRequestGuard;
import dev.jiaming.ai_interview.common.RuntimeModeProperties;
import dev.jiaming.ai_interview.document.DocumentReferenceResolver;
import dev.jiaming.ai_interview.document.ResolvedJobInputs;

@Service
public class JobSubmissionService {

	private static final Logger log = LoggerFactory.getLogger(JobSubmissionService.class);

	private final BackgroundJobStore jobStore;

	private final JobDispatcher dispatcher;

	private final RequestFingerprintService fingerprintService;

	private final LocalUserService localUserService;

	private final RedisRequestGuard requestGuard;

	private final DocumentReferenceResolver documentResolver;

	private final JobProperties properties;

	private final RuntimeModeProperties runtimeMode;

	private final JobMetrics metrics;

	private final ObjectMapper objectMapper;

	private final TransactionOperations transactionOperations;

	@Autowired
	public JobSubmissionService(
		BackgroundJobStore jobStore,
		JobDispatcher dispatcher,
		RequestFingerprintService fingerprintService,
		LocalUserService localUserService,
		RedisRequestGuard requestGuard,
		DocumentReferenceResolver documentResolver,
		JobProperties properties,
		RuntimeModeProperties runtimeMode,
		JobMetrics metrics,
		ObjectMapper objectMapper,
		TransactionOperations transactionOperations
	) {
		this.jobStore = jobStore;
		this.dispatcher = dispatcher;
		this.fingerprintService = fingerprintService;
		this.localUserService = localUserService;
		this.requestGuard = requestGuard;
		this.documentResolver = documentResolver;
		this.properties = properties;
		this.runtimeMode = runtimeMode;
		this.metrics = metrics;
		this.objectMapper = objectMapper;
		this.transactionOperations = transactionOperations;
	}

	public JobSubmissionService(
		BackgroundJobStore jobStore,
		JobDispatcher dispatcher,
		RequestFingerprintService fingerprintService,
		LocalUserService localUserService,
		RedisRequestGuard requestGuard,
		DocumentReferenceResolver documentResolver,
		JobProperties properties,
		RuntimeModeProperties runtimeMode,
		JobMetrics metrics,
		ObjectMapper objectMapper
	) {
		this(
			jobStore,
			dispatcher,
			fingerprintService,
			localUserService,
			requestGuard,
			documentResolver,
			properties,
			runtimeMode,
			metrics,
			objectMapper,
			TransactionOperations.withoutTransaction()
		);
	}

	public JobAcceptedResponse submitAnalysis(AiAnalysisRequest request) {
		assertApiAvailable();
		return submitWithHttpProtection("analysis", JobType.ANALYSIS, request, () -> {
			requestGuard.assertAiAllowed("analysis");
			return inTransaction(() -> submitAnalysisTransaction(request));
		});
	}

	private JobAcceptedResponse submitAnalysisTransaction(AiAnalysisRequest request) {
		UUID userId = localUserService.localUserId();
		ResolvedJobInputs inputs = documentResolver.resolveForSubmission(
			userId,
			request.resumeId(),
			request.resumeText(),
			request.jobDescriptionId(),
			request.jobDescription()
		);
		AnalysisJobPayload payload = new AnalysisJobPayload(
			inputs.resume().resourceId(),
			inputs.jobDescription().map(document -> document.resourceId()).orElse(null),
			trim(request.targetRole()),
			trim(request.seniority())
		);
		AnalysisFingerprint fingerprintSource = new AnalysisFingerprint(
			inputs.resume().contentHash(),
			inputs.jobDescription().map(document -> document.contentHash()).orElse(""),
			normalizeLabel(request.targetRole()),
			normalizeLabel(request.seniority())
		);
		return createOrReuse(
			JobType.ANALYSIS,
			"resume",
			inputs.resume().resourceId(),
			payload,
			fingerprint("analysis", fingerprintSource)
		);
	}

	public JobAcceptedResponse submitFeedback(AnswerFeedbackRequest request) {
		assertApiAvailable();
		if (request.answerText() == null || request.answerText().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Answer text is required");
		}
		return submitWithHttpProtection("answer-feedback", JobType.ANSWER_FEEDBACK, request, () -> {
			requestGuard.assertAiAllowed("answer-feedback");
			return inTransaction(() -> submitFeedbackTransaction(request));
		});
	}

	private JobAcceptedResponse submitFeedbackTransaction(AnswerFeedbackRequest request) {
		UUID userId = localUserService.localUserId();
		ResolvedJobInputs inputs = documentResolver.resolveForSubmission(
			userId,
			request.resumeId(),
			request.resumeText(),
			request.jobDescriptionId(),
			request.jobDescription()
		);
		FeedbackJobPayload payload = new FeedbackJobPayload(
			inputs.resume().resourceId(),
			inputs.jobDescription().map(document -> document.resourceId()).orElse(null),
			trim(request.targetRole()),
			trim(request.seniority()),
			trim(request.questionText()),
			trim(request.category()),
			request.expectedSignals(),
			request.answerText().trim()
		);
		FeedbackFingerprint fingerprintSource = new FeedbackFingerprint(
			inputs.resume().contentHash(),
			inputs.jobDescription().map(document -> document.contentHash()).orElse(""),
			normalizeLabel(request.targetRole()),
			normalizeLabel(request.seniority()),
			trim(request.questionText()),
			normalizeLabel(request.category()),
			request.expectedSignals() == null ? List.of() : List.copyOf(request.expectedSignals()),
			request.answerText().trim()
		);
		return createOrReuse(
			JobType.ANSWER_FEEDBACK,
			"interview-answer",
			inputs.resume().resourceId(),
			payload,
			fingerprint("answer-feedback", fingerprintSource)
		);
	}

	public String fingerprint(String action, Object source) {
		return fingerprintService.fingerprint(action, source);
	}

	public void assertApiAvailable() {
		if (!properties.enabled() || !runtimeMode.apiEnabled()) {
			throw new ResponseStatusException(
				HttpStatus.SERVICE_UNAVAILABLE,
				"This process is running in worker-only mode and does not accept background jobs"
			);
		}
	}

	public Optional<JobAcceptedResponse> findReusable(JobType type, String fingerprint) {
		UUID userId = localUserService.localUserId();
		return jobStore.findReusable(userId, type, fingerprint, properties.reuseWindow())
			.map(job -> JobAcceptedResponse.from(job, true));
	}

	public JobAcceptedResponse createOrReuse(
		JobType type,
		String resourceType,
		UUID resourceId,
		Object requestPayload,
		String fingerprint
	) {
		Optional<JobAcceptedResponse> existing = findReusable(type, fingerprint);
		if (existing.isPresent()) {
			return existing.get();
		}

		Optional<BackgroundJob> created = jobStore.createIfAbsent(
			localUserService.localUserId(),
			type,
			resourceType,
			resourceId,
			objectMapper.valueToTree(requestPayload),
			fingerprint,
			properties.maxAttempts()
		);
		if (created.isEmpty()) {
			return findReusable(type, fingerprint).orElseThrow(() -> new IllegalStateException(
				"A matching active job won the submission race but could not be loaded"
			));
		}
		BackgroundJob job = created.get();

		metrics.submitted(type);
		log.info("job_submitted jobId={} type={} userId={} resourceType={} resourceId={}",
			job.id(), type, job.userId(), resourceType, resourceId);
		dispatchAfterCommit(job.id());
		return JobAcceptedResponse.from(job, false);
	}

	public <T> T withIdempotency(String action, Object fingerprintSource, Class<T> type, java.util.function.Supplier<T> work) {
		return requestGuard.withIdempotentRetryCache(action, fingerprintSource, type, work);
	}

	private JobAcceptedResponse submitWithHttpProtection(
		String action,
		JobType type,
		Object fingerprintSource,
		java.util.function.Supplier<JobAcceptedResponse> work
	) {
		return requestGuard.withIdempotentRetryCache(action, fingerprintSource, JobAcceptedResponse.class, work);
	}

	private void dispatchAfterCommit(UUID jobId) {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			tryDispatch(jobId);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				tryDispatch(jobId);
			}
		});
	}

	private void tryDispatch(UUID jobId) {
		try {
			dispatcher.dispatch(jobId);
		}
		catch (RuntimeException exception) {
			log.warn("job_initial_dispatch_failed jobId={} reason={}", jobId, exception.getMessage());
		}
	}

	private <T> T inTransaction(java.util.function.Supplier<T> work) {
		return Objects.requireNonNull(transactionOperations.execute(status -> work.get()));
	}

	private String normalizeLabel(String value) {
		return trim(value).replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
	}

	private String trim(String value) {
		return value == null ? "" : value.trim();
	}

	private record AnalysisFingerprint(
		String resumeHash,
		String jobDescriptionHash,
		String targetRole,
		String seniority
	) {
	}

	private record FeedbackFingerprint(
		String resumeHash,
		String jobDescriptionHash,
		String targetRole,
		String seniority,
		String question,
		String category,
		List<String> expectedSignals,
		String answer
	) {
	}
}
