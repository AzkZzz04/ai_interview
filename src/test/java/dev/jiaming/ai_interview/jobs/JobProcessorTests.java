package dev.jiaming.ai_interview.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import dev.jiaming.ai_interview.coach.AiAnalysisRequest;
import dev.jiaming.ai_interview.coach.AiResumeCoachService;
import dev.jiaming.ai_interview.coach.AnswerFeedbackRequest;
import dev.jiaming.ai_interview.coach.AnswerFeedbackResponse;
import dev.jiaming.ai_interview.coach.AssessmentResponse;
import dev.jiaming.ai_interview.coach.AssessmentScores;
import dev.jiaming.ai_interview.coach.InterviewQuestionResponse;
import dev.jiaming.ai_interview.coach.InterviewQuestionsResponse;
import dev.jiaming.ai_interview.resume.ResumeExtractionJobHandler;

class JobProcessorTests {

	private final BackgroundJobStore jobStore = mock(BackgroundJobStore.class);

	private final AiResumeCoachService coachService = mock(AiResumeCoachService.class);

	private final JobEffectMaterializationService effectMaterializationService = mock(JobEffectMaterializationService.class);

	private final ResumeExtractionJobHandler extractionHandler = mock(ResumeExtractionJobHandler.class);

	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	private final JobProcessor processor = new JobProcessor(
		jobStore,
		coachService,
		effectMaterializationService,
		extractionHandler,
		objectMapper
	);

	@Test
	void checkpointsAssessmentBeforeGeneratingQuestions() {
		AiAnalysisRequest request = request();
		AssessmentResponse assessment = assessment();
		InterviewQuestionsResponse questions = questions();
		BackgroundJob job = analysisJob(objectMapper.valueToTree(request), null);
		UUID leaseToken = UUID.randomUUID();
		when(coachService.runAssessment(request, request.resumeText())).thenReturn(assessment);
		when(coachService.runQuestions(request, request.resumeText())).thenReturn(questions);

		var result = processor.process(job, leaseToken);

		assertThat(result.get("assessment").get("overallScore").asInt()).isEqualTo(82);
		assertThat(result.has("questions")).isTrue();
		InOrder order = inOrder(jobStore, coachService, effectMaterializationService);
		order.verify(jobStore).updateStage(job.id(), leaseToken, JobStage.ASSESSING_RESUME);
		order.verify(coachService).runAssessment(request, request.resumeText());
		order.verify(jobStore).checkpointResult(eq(job.id()), eq(leaseToken), any(ObjectNode.class));
		order.verify(effectMaterializationService).materializeAssessment(job, leaseToken, request, assessment);
		order.verify(jobStore).updateStage(job.id(), leaseToken, JobStage.GENERATING_QUESTIONS);
		order.verify(coachService).runQuestions(request, request.resumeText());
		order.verify(jobStore).checkpointResult(eq(job.id()), eq(leaseToken), any(ObjectNode.class));
		order.verify(effectMaterializationService).materializeQuestions(job, leaseToken, request, questions);
	}

	@Test
	void retryReusesAllAnalysisCheckpointsAndMaterializesMissingEffects() {
		AiAnalysisRequest request = request();
		ObjectNode checkpoint = objectMapper.createObjectNode();
		checkpoint.set("assessment", objectMapper.valueToTree(assessment()));
		checkpoint.set("questions", objectMapper.valueToTree(questions()));
		BackgroundJob job = analysisJob(objectMapper.valueToTree(request), checkpoint);
		UUID leaseToken = UUID.randomUUID();

		processor.process(job, leaseToken);

		verify(coachService, never()).runAssessment(any(), any());
		verify(coachService, never()).runQuestions(any(), any());
		verify(jobStore, never()).checkpointResult(any(), any(), any());
		verify(effectMaterializationService).materializeAssessment(job, leaseToken, request, assessment());
		verify(effectMaterializationService).materializeQuestions(job, leaseToken, request, questions());
	}

	@Test
	void questionFailureLeavesAssessmentCheckpointSaved() {
		AiAnalysisRequest request = request();
		BackgroundJob job = analysisJob(objectMapper.valueToTree(request), null);
		UUID leaseToken = UUID.randomUUID();
		when(coachService.runAssessment(request, request.resumeText())).thenReturn(assessment());
		when(coachService.runQuestions(request, request.resumeText())).thenThrow(new RuntimeException("timeout"));

		assertThatThrownBy(() -> processor.process(job, leaseToken)).hasMessage("timeout");

		verify(jobStore).checkpointResult(eq(job.id()), eq(leaseToken), any(ObjectNode.class));
		verify(effectMaterializationService).materializeAssessment(job, leaseToken, request, assessment());
		verify(effectMaterializationService, never()).materializeQuestions(any(), any(), any(), any());
	}

	@Test
	void feedbackIsCheckpointedBeforeItIsMaterialized() {
		AnswerFeedbackRequest request = feedbackRequest();
		AnswerFeedbackResponse feedback = feedback();
		BackgroundJob job = feedbackJob(objectMapper.valueToTree(request), null);
		UUID leaseToken = UUID.randomUUID();
		when(coachService.runFeedback(request, request.resumeText())).thenReturn(feedback);

		var result = processor.process(job, leaseToken);

		assertThat(result.get("score").asInt()).isEqualTo(88);
		InOrder order = inOrder(jobStore, coachService, effectMaterializationService);
		order.verify(jobStore).updateStage(job.id(), leaseToken, JobStage.SCORING_ANSWER);
		order.verify(coachService).runFeedback(request, request.resumeText());
		order.verify(jobStore).checkpointResult(job.id(), leaseToken, objectMapper.valueToTree(feedback));
		order.verify(effectMaterializationService).materializeFeedback(job, leaseToken, request, feedback);
	}

	@Test
	void feedbackRetryReusesCheckpointWithoutCallingGemini() {
		AnswerFeedbackRequest request = feedbackRequest();
		AnswerFeedbackResponse feedback = feedback();
		BackgroundJob job = feedbackJob(objectMapper.valueToTree(request), objectMapper.valueToTree(feedback));
		UUID leaseToken = UUID.randomUUID();

		processor.process(job, leaseToken);

		verify(coachService, never()).runFeedback(any(), any());
		verify(jobStore, never()).checkpointResult(any(), any(), any());
		verify(effectMaterializationService).materializeFeedback(job, leaseToken, request, feedback);
	}

	private AiAnalysisRequest request() {
		return new AiAnalysisRequest("resume text", "job description", "Backend Engineer", "Mid-level");
	}

	private AssessmentResponse assessment() {
		return new AssessmentResponse(
			82,
			new AssessmentScores(84, 80, 82, 83, 81),
			List.of("Clear backend experience"),
			List.of("Add metrics"),
			List.of(),
			"Gemini",
			List.of("resume:0")
		);
	}

	private InterviewQuestionsResponse questions() {
		return new InterviewQuestionsResponse(
			List.of(new InterviewQuestionResponse(
				"spring-design",
				"System Design",
				"Core",
				"How would you design a resilient Spring service?",
				List.of("Retries", "Idempotency"),
				List.of("resume:0")
			)),
			"gemini"
		);
	}

	private AnswerFeedbackRequest feedbackRequest() {
		return new AnswerFeedbackRequest(
			"resume text",
			"job description",
			"Backend Engineer",
			"Mid-level",
			"How would you design a resilient Spring service?",
			"System Design",
			List.of("Retries", "Idempotency"),
			"I would use durable jobs and idempotent writes."
		);
	}

	private AnswerFeedbackResponse feedback() {
		return new AnswerFeedbackResponse(
			88,
			"Strong structure",
			"Add failure recovery details",
			List.of("Clear tradeoffs"),
			List.of("Missing metrics"),
			List.of("State assumptions", "Describe recovery"),
			"How would you test duplicate delivery?",
			"gemini",
			List.of("resume:0")
		);
	}

	private BackgroundJob analysisJob(com.fasterxml.jackson.databind.JsonNode request, com.fasterxml.jackson.databind.JsonNode result) {
		return job(JobType.ANALYSIS, request, result);
	}

	private BackgroundJob feedbackJob(com.fasterxml.jackson.databind.JsonNode request, com.fasterxml.jackson.databind.JsonNode result) {
		return job(JobType.ANSWER_FEEDBACK, request, result);
	}

	private BackgroundJob job(
		JobType jobType,
		com.fasterxml.jackson.databind.JsonNode request,
		com.fasterxml.jackson.databind.JsonNode result
	) {
		Instant now = Instant.now();
		return new BackgroundJob(
			UUID.randomUUID(), UUID.randomUUID(), jobType, "resume", null,
			JobStatus.PROCESSING, JobStage.QUEUED, request, result, "fingerprint", 1, 3,
			null, null, null, now, now, now, now, now, null, UUID.randomUUID(), now.plusSeconds(300)
		);
	}
}
