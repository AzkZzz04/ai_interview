package dev.jiaming.ai_interview.jobs;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import dev.jiaming.ai_interview.coach.AiResumeCoachService;
import dev.jiaming.ai_interview.coach.AssessmentResponse;
import dev.jiaming.ai_interview.coach.AssessmentScores;
import dev.jiaming.ai_interview.coach.CoachAnalysisInput;
import dev.jiaming.ai_interview.coach.InterviewQuestionResponse;
import dev.jiaming.ai_interview.coach.InterviewQuestionsResponse;
import dev.jiaming.ai_interview.document.DocumentReferenceResolver;
import dev.jiaming.ai_interview.document.DocumentSourceType;
import dev.jiaming.ai_interview.document.ResolvedDocument;
import dev.jiaming.ai_interview.document.ResolvedJobInputs;
import dev.jiaming.ai_interview.interview.AnalysisPersistenceInput;

class AnalysisJobHandlerTests {

	private final AiResumeCoachService coach = mock(AiResumeCoachService.class);

	private final DocumentReferenceResolver resolver = mock(DocumentReferenceResolver.class);

	private final AnalysisJobHandler handler = new AnalysisJobHandler(coach, resolver);

	private final JobExecutionContext context = mock(JobExecutionContext.class);

	@Test
	void checkpointsAssessmentBeforeGeneratingQuestions() {
		AnalysisJobPayload payload = payload();
		ResolvedJobInputs documents = documents(payload);
		CoachAnalysisInput input = coachInput(documents, payload);
		AssessmentResponse assessment = assessment();
		InterviewQuestionsResponse questions = questions();
		when(context.userId()).thenReturn(UUID.randomUUID());
		when(resolver.resolveStrict(context.userId(), payload.resumeId(), payload.jobDescriptionId()))
			.thenReturn(documents);
		when(coach.assess(input)).thenReturn(assessment);
		when(coach.generateQuestions(input)).thenReturn(questions);

		handler.handle(payload, context);

		InOrder order = inOrder(context, coach);
		order.verify(context).stage(JobStage.ASSESSING_RESUME);
		order.verify(coach).assess(input);
		order.verify(context).saveCheckpoint("assessment", assessment);
		order.verify(context).materializeAssessment(any(AnalysisPersistenceInput.class), org.mockito.ArgumentMatchers.eq(assessment));
		order.verify(context).stage(JobStage.GENERATING_QUESTIONS);
		order.verify(coach).generateQuestions(input);
		order.verify(context).saveCheckpoint("questions", questions);
		order.verify(context).materializeQuestions(any(AnalysisPersistenceInput.class), org.mockito.ArgumentMatchers.eq(questions));
	}

	@Test
	void retryUsesAssessmentCheckpointAndDoesNotRepeatAssessment() {
		AnalysisJobPayload payload = payload();
		ResolvedJobInputs documents = documents(payload);
		AssessmentResponse assessment = assessment();
		InterviewQuestionsResponse questions = questions();
		when(context.userId()).thenReturn(UUID.randomUUID());
		when(resolver.resolveStrict(context.userId(), payload.resumeId(), payload.jobDescriptionId()))
			.thenReturn(documents);
		when(context.checkpoint("assessment", AssessmentResponse.class)).thenReturn(assessment);
		when(context.checkpoint("questions", InterviewQuestionsResponse.class)).thenReturn(questions);

		handler.handle(payload, context);

		verify(coach, never()).assess(any(CoachAnalysisInput.class));
		verify(coach, never()).generateQuestions(any(CoachAnalysisInput.class));
		verify(context).materializeAssessment(any(), org.mockito.ArgumentMatchers.eq(assessment));
		verify(context).materializeQuestions(any(), org.mockito.ArgumentMatchers.eq(questions));
	}

	@Test
	void questionFailureLeavesSavedAssessmentCheckpointForPartialResult() {
		AnalysisJobPayload payload = payload();
		ResolvedJobInputs documents = documents(payload);
		CoachAnalysisInput input = coachInput(documents, payload);
		AssessmentResponse assessment = assessment();
		when(context.userId()).thenReturn(UUID.randomUUID());
		when(resolver.resolveStrict(context.userId(), payload.resumeId(), payload.jobDescriptionId()))
			.thenReturn(documents);
		when(coach.assess(input)).thenReturn(assessment);
		when(coach.generateQuestions(input)).thenThrow(new RuntimeException("timeout"));

		assertThatThrownBy(() -> handler.handle(payload, context)).hasMessage("timeout");

		verify(context).saveCheckpoint("assessment", assessment);
		verify(context).materializeAssessment(any(), org.mockito.ArgumentMatchers.eq(assessment));
		verify(context, never()).saveCheckpoint(org.mockito.ArgumentMatchers.eq("questions"), any());
		verify(context, never()).materializeQuestions(any(), any());
	}

	private AnalysisJobPayload payload() {
		return new AnalysisJobPayload(UUID.randomUUID(), UUID.randomUUID(), "Backend", "Mid-level");
	}

	private ResolvedJobInputs documents(AnalysisJobPayload payload) {
		return new ResolvedJobInputs(
			new ResolvedDocument(DocumentSourceType.RESUME, payload.resumeId(), "resume-hash", "resume", List.of()),
			Optional.of(new ResolvedDocument(
				DocumentSourceType.JOB_DESCRIPTION,
				payload.jobDescriptionId(),
				"jd-hash",
				"job",
				List.of()
			))
		);
	}

	private CoachAnalysisInput coachInput(ResolvedJobInputs documents, AnalysisJobPayload payload) {
		return new CoachAnalysisInput(
			documents.resume(), documents.jobDescription(), payload.targetRole(), payload.seniority()
		);
	}

	private AssessmentResponse assessment() {
		return new AssessmentResponse(
			82,
			new AssessmentScores(84, 80, 82, 83, 81),
			List.of("Clear backend experience"),
			List.of("Add metrics"),
			List.of(),
			"Gemini",
			List.of("resume:experience:0")
		);
	}

	private InterviewQuestionsResponse questions() {
		return new InterviewQuestionsResponse(
			List.of(new InterviewQuestionResponse(
				"spring-design", "System Design", "Core", "How would you design it?",
				List.of("Retries"), List.of("resume:experience:0")
			)),
			"gemini"
		);
	}
}
