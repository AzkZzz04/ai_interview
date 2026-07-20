package dev.jiaming.ai_interview.jobs;

import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.jiaming.ai_interview.coach.AiAnalysisRequest;
import dev.jiaming.ai_interview.coach.AiResumeCoachService;
import dev.jiaming.ai_interview.coach.AnswerFeedbackRequest;
import dev.jiaming.ai_interview.coach.AnswerFeedbackResponse;
import dev.jiaming.ai_interview.coach.AssessmentResponse;
import dev.jiaming.ai_interview.coach.InterviewQuestionsResponse;
import dev.jiaming.ai_interview.resume.ResumeExtractionJobHandler;
import dev.jiaming.ai_interview.resume.ResumeExtractionJobPayload;

@Service
public class JobProcessor {

	private static final Logger log = LoggerFactory.getLogger(JobProcessor.class);

	private final BackgroundJobStore jobStore;

	private final AiResumeCoachService coachService;

	private final JobEffectMaterializationService effectMaterializationService;

	private final ResumeExtractionJobHandler resumeExtractionHandler;

	private final ObjectMapper objectMapper;

	public JobProcessor(
		BackgroundJobStore jobStore,
		AiResumeCoachService coachService,
		JobEffectMaterializationService effectMaterializationService,
		ResumeExtractionJobHandler resumeExtractionHandler,
		ObjectMapper objectMapper
	) {
		this.jobStore = jobStore;
		this.coachService = coachService;
		this.effectMaterializationService = effectMaterializationService;
		this.resumeExtractionHandler = resumeExtractionHandler;
		this.objectMapper = objectMapper;
	}

	public JsonNode process(BackgroundJob job, UUID leaseToken) {
		return switch (job.jobType()) {
			case RESUME_EXTRACTION -> processResume(job, leaseToken);
			case ANALYSIS -> processAnalysis(job, leaseToken);
			case ANSWER_FEEDBACK -> processFeedback(job, leaseToken);
		};
	}

	private JsonNode processResume(BackgroundJob job, UUID leaseToken) {
		ResumeExtractionJobPayload payload = convert(job.requestPayload(), ResumeExtractionJobPayload.class);
		var response = resumeExtractionHandler.extract(payload, stage -> updateStage(job, leaseToken, stage));
		return objectMapper.valueToTree(response);
	}

	private JsonNode processAnalysis(BackgroundJob job, UUID leaseToken) {
		AiAnalysisRequest request = convert(job.requestPayload(), AiAnalysisRequest.class);
		AssessmentResponse assessment = checkpointedAssessment(job.resultPayload());
		ObjectNode checkpoint = checkpointObject(job.resultPayload());

		updateStage(job, leaseToken, JobStage.ASSESSING_RESUME);
		if (assessment == null) {
			assessment = coachService.runAssessment(request, request.resumeText());
			checkpoint.set("assessment", objectMapper.valueToTree(assessment));
			jobStore.checkpointResult(job.id(), leaseToken, checkpoint);
			log.info("job_checkpoint_saved jobId={} checkpoint=assessment", job.id());
		}
		effectMaterializationService.materializeAssessment(job, leaseToken, request, assessment);

		updateStage(job, leaseToken, JobStage.GENERATING_QUESTIONS);
		InterviewQuestionsResponse questions = checkpointedQuestions(job.resultPayload());
		if (questions == null) {
			questions = coachService.runQuestions(request, request.resumeText());
			checkpoint.set("questions", objectMapper.valueToTree(questions));
			jobStore.checkpointResult(job.id(), leaseToken, checkpoint);
			log.info("job_checkpoint_saved jobId={} checkpoint=questions", job.id());
		}
		effectMaterializationService.materializeQuestions(job, leaseToken, request, questions);
		return objectMapper.valueToTree(new AnalysisJobResult(assessment, questions));
	}

	private JsonNode processFeedback(BackgroundJob job, UUID leaseToken) {
		updateStage(job, leaseToken, JobStage.SCORING_ANSWER);
		AnswerFeedbackRequest request = convert(job.requestPayload(), AnswerFeedbackRequest.class);
		AnswerFeedbackResponse feedback = checkpointedFeedback(job.resultPayload());
		if (feedback == null) {
			feedback = coachService.runFeedback(request, request.resumeText());
			jobStore.checkpointResult(job.id(), leaseToken, objectMapper.valueToTree(feedback));
			log.info("job_checkpoint_saved jobId={} checkpoint=answer-feedback", job.id());
		}
		effectMaterializationService.materializeFeedback(job, leaseToken, request, feedback);
		return objectMapper.valueToTree(feedback);
	}

	private AssessmentResponse checkpointedAssessment(JsonNode result) {
		if (result == null || !result.hasNonNull("assessment")) {
			return null;
		}
		return convert(result.get("assessment"), AssessmentResponse.class);
	}

	private InterviewQuestionsResponse checkpointedQuestions(JsonNode result) {
		if (result == null || !result.hasNonNull("questions")) {
			return null;
		}
		return convert(result.get("questions"), InterviewQuestionsResponse.class);
	}

	private AnswerFeedbackResponse checkpointedFeedback(JsonNode result) {
		if (result == null || !result.isObject() || !result.has("score")) {
			return null;
		}
		return convert(result, AnswerFeedbackResponse.class);
	}

	private ObjectNode checkpointObject(JsonNode result) {
		if (result instanceof ObjectNode objectNode) {
			return objectNode.deepCopy();
		}
		return objectMapper.createObjectNode();
	}

	private void updateStage(BackgroundJob job, UUID leaseToken, JobStage stage) {
		jobStore.updateStage(job.id(), leaseToken, stage);
		log.info("job_stage_changed jobId={} type={} stage={}", job.id(), job.jobType(), stage);
	}

	private <T> T convert(JsonNode node, Class<T> type) {
		try {
			return objectMapper.treeToValue(node, type);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Invalid " + type.getSimpleName() + " job payload", exception);
		}
	}
}
