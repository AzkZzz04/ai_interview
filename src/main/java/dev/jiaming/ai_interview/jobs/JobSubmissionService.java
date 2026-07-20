package dev.jiaming.ai_interview.jobs;

import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import dev.jiaming.ai_interview.coach.AiAnalysisRequest;
import dev.jiaming.ai_interview.coach.AnswerFeedbackRequest;
import dev.jiaming.ai_interview.coach.CoachResumeResolver;
import dev.jiaming.ai_interview.common.LocalUserService;
import dev.jiaming.ai_interview.common.RedisRequestGuard;

@Service
public class JobSubmissionService {

	private static final Logger log = LoggerFactory.getLogger(JobSubmissionService.class);

	private final BackgroundJobStore jobStore;

	private final JobDispatcher dispatcher;

	private final JobResultCache resultCache;

	private final RequestFingerprintService fingerprintService;

	private final LocalUserService localUserService;

	private final RedisRequestGuard requestGuard;

	private final CoachResumeResolver resumeResolver;

	private final JobProperties properties;

	private final JobMetrics metrics;

	private final ObjectMapper objectMapper;

	public JobSubmissionService(
		BackgroundJobStore jobStore,
		JobDispatcher dispatcher,
		JobResultCache resultCache,
		RequestFingerprintService fingerprintService,
		LocalUserService localUserService,
		RedisRequestGuard requestGuard,
		CoachResumeResolver resumeResolver,
		JobProperties properties,
		JobMetrics metrics,
		ObjectMapper objectMapper
	) {
		this.jobStore = jobStore;
		this.dispatcher = dispatcher;
		this.resultCache = resultCache;
		this.fingerprintService = fingerprintService;
		this.localUserService = localUserService;
		this.requestGuard = requestGuard;
		this.resumeResolver = resumeResolver;
		this.properties = properties;
		this.metrics = metrics;
		this.objectMapper = objectMapper;
	}

	public JobAcceptedResponse submitAnalysis(AiAnalysisRequest request) {
		assertApiAvailable();
		String resumeText = resumeResolver.resolve(request.resumeText());
		AiAnalysisRequest prepared = new AiAnalysisRequest(
			resumeText,
			request.jobDescription(),
			request.targetRole(),
			request.seniority()
		);
		return submitWithHttpProtection("analysis", JobType.ANALYSIS, prepared, () -> {
			requestGuard.assertAiAllowed("analysis");
			return createOrReuse(JobType.ANALYSIS, "resume", null, prepared, fingerprint("analysis", prepared));
		});
	}

	public JobAcceptedResponse submitFeedback(AnswerFeedbackRequest request) {
		assertApiAvailable();
		if (request.answerText() == null || request.answerText().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Answer text is required");
		}
		String resumeText = resumeResolver.resolve(request.resumeText());
		AnswerFeedbackRequest prepared = new AnswerFeedbackRequest(
			resumeText,
			request.jobDescription(),
			request.targetRole(),
			request.seniority(),
			request.questionText(),
			request.category(),
			request.expectedSignals(),
			request.answerText()
		);
		return submitWithHttpProtection("answer-feedback", JobType.ANSWER_FEEDBACK, prepared, () -> {
			requestGuard.assertAiAllowed("answer-feedback");
			return createOrReuse(
				JobType.ANSWER_FEEDBACK,
				"interview-answer",
				null,
				prepared,
				fingerprint("answer-feedback", prepared)
			);
		});
	}

	public String fingerprint(String action, Object source) {
		return fingerprintService.fingerprint(action, source);
	}

	public void assertApiAvailable() {
		if (!properties.apiEnabled()) {
			throw new ResponseStatusException(
				HttpStatus.SERVICE_UNAVAILABLE,
				"This process is running in worker-only mode and does not accept background jobs"
			);
		}
	}

	public Optional<JobAcceptedResponse> findReusable(JobType type, String fingerprint) {
		UUID userId = localUserService.localUserId();
		Optional<BackgroundJob> cached = resultCache.find(userId, type, fingerprint)
			.flatMap(jobStore::findById)
			.filter(job -> reusable(job, userId, type, fingerprint));
		if (cached.isPresent()) {
			return cached.map(job -> JobAcceptedResponse.from(job, true));
		}
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

		BackgroundJob job;
		try {
			job = jobStore.create(
				localUserService.localUserId(),
				type,
				resourceType,
				resourceId,
				objectMapper.valueToTree(requestPayload),
				fingerprint,
				properties.maxAttempts()
			);
		}
		catch (DuplicateKeyException exception) {
			return findReusable(type, fingerprint).orElseThrow(() -> exception);
		}

		metrics.submitted(type);
		log.info("job_submitted jobId={} type={} userId={} resourceType={} resourceId={}",
			job.id(), type, job.userId(), resourceType, resourceId);
		dispatcher.dispatch(job.id());
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

	private boolean reusable(BackgroundJob job, UUID userId, JobType type, String fingerprint) {
		return userId.equals(job.userId())
			&& type == job.jobType()
			&& fingerprint.equals(job.requestFingerprint())
			&& (job.status() == JobStatus.SUCCEEDED || job.status() == JobStatus.PARTIAL);
	}
}
