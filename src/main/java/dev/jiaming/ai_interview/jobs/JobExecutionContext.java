package dev.jiaming.ai_interview.jobs;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.jiaming.ai_interview.coach.AnswerFeedbackResponse;
import dev.jiaming.ai_interview.coach.AssessmentResponse;
import dev.jiaming.ai_interview.coach.InterviewQuestionsResponse;
import dev.jiaming.ai_interview.interview.AnalysisPersistenceInput;
import dev.jiaming.ai_interview.interview.FeedbackPersistenceInput;

public final class JobExecutionContext {

	private static final Logger log = LoggerFactory.getLogger(JobExecutionContext.class);

	private final BackgroundJob job;

	private final UUID leaseToken;

	private final BackgroundJobStore jobStore;

	private final JobEffectMaterializationService materializationService;

	private final JobMetrics metrics;

	private final ObjectMapper objectMapper;

	private final ObjectNode checkpointState;

	private JobStage activeStage;

	private Instant stageStartedAt;

	JobExecutionContext(
		BackgroundJob job,
		UUID leaseToken,
		BackgroundJobStore jobStore,
		JobEffectMaterializationService materializationService,
		JobMetrics metrics,
		ObjectMapper objectMapper
	) {
		this.job = job;
		this.leaseToken = leaseToken;
		this.jobStore = jobStore;
		this.materializationService = materializationService;
		this.metrics = metrics;
		this.objectMapper = objectMapper;
		this.checkpointState = job.resultPayload() instanceof ObjectNode objectNode
			? objectNode.deepCopy()
			: objectMapper.createObjectNode();
	}

	public BackgroundJob job() {
		return job;
	}

	public UUID userId() {
		if (job.userId() == null) {
			throw new IllegalArgumentException("Background job has no user: " + job.id());
		}
		return job.userId();
	}

	public void stage(JobStage stage) {
		finishActiveStage();
		jobStore.updateStage(job.id(), leaseToken, stage);
		activeStage = stage;
		stageStartedAt = Instant.now();
		log.info("job_stage_changed jobId={} type={} stage={} attempt={}",
			job.id(), job.jobType(), stage, job.attempts());
	}

	public <T> T checkpoint(String field, Class<T> type) {
		if (!checkpointState.hasNonNull(field)) {
			return null;
		}
		return convert(checkpointState.get(field), type);
	}

	public <T> T rootCheckpoint(Class<T> type, String requiredField) {
		JsonNode result = job.resultPayload();
		if (result == null || !result.isObject() || !result.has(requiredField)) {
			return null;
		}
		return convert(result, type);
	}

	public void saveCheckpoint(String field, Object value) {
		checkpointState.set(field, objectMapper.valueToTree(value));
		jobStore.checkpointResult(job.id(), leaseToken, checkpointState.deepCopy());
		log.info("job_checkpoint_saved jobId={} type={} checkpoint={}", job.id(), job.jobType(), field);
	}

	public void saveRootCheckpoint(Object value, String checkpointName) {
		jobStore.checkpointResult(job.id(), leaseToken, objectMapper.valueToTree(value));
		log.info("job_checkpoint_saved jobId={} type={} checkpoint={}",
			job.id(), job.jobType(), checkpointName);
	}

	public UUID materializeAssessment(AnalysisPersistenceInput input, AssessmentResponse response) {
		return materializationService.materializeAssessment(job, leaseToken, input, response);
	}

	public UUID materializeQuestions(AnalysisPersistenceInput input, InterviewQuestionsResponse response) {
		return materializationService.materializeQuestions(job, leaseToken, input, response);
	}

	public UUID materializeFeedback(FeedbackPersistenceInput input, AnswerFeedbackResponse response) {
		return materializationService.materializeFeedback(job, leaseToken, input, response);
	}

	public <T> T withOwnedLease(Supplier<T> work) {
		return materializationService.withOwnedLease(job, leaseToken, work);
	}

	public JsonNode toJson(Object value) {
		return objectMapper.valueToTree(value);
	}

	void finish() {
		finishActiveStage();
	}

	private <T> T convert(JsonNode node, Class<T> type) {
		try {
			return objectMapper.treeToValue(node, type);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Invalid " + type.getSimpleName() + " checkpoint", exception);
		}
	}

	private void finishActiveStage() {
		if (activeStage == null || stageStartedAt == null) {
			return;
		}
		metrics.stageDuration(job.jobType(), activeStage, Duration.between(stageStartedAt, Instant.now()));
		activeStage = null;
		stageStartedAt = null;
	}
}
