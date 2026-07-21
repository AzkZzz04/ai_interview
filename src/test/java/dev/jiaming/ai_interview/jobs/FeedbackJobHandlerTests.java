package dev.jiaming.ai_interview.jobs;

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
import dev.jiaming.ai_interview.coach.AnswerFeedbackResponse;
import dev.jiaming.ai_interview.coach.CoachFeedbackInput;
import dev.jiaming.ai_interview.document.DocumentReferenceResolver;
import dev.jiaming.ai_interview.document.DocumentSourceType;
import dev.jiaming.ai_interview.document.ResolvedDocument;
import dev.jiaming.ai_interview.document.ResolvedJobInputs;
import dev.jiaming.ai_interview.interview.FeedbackPersistenceInput;

class FeedbackJobHandlerTests {

	@Test
	void checkpointsFeedbackBeforeMaterializingIt() {
		AiResumeCoachService coach = mock(AiResumeCoachService.class);
		DocumentReferenceResolver resolver = mock(DocumentReferenceResolver.class);
		JobExecutionContext context = mock(JobExecutionContext.class);
		FeedbackJobHandler handler = new FeedbackJobHandler(coach, resolver);
		UUID userId = UUID.randomUUID();
		FeedbackJobPayload payload = payload();
		ResolvedJobInputs documents = documents(payload);
		CoachFeedbackInput input = input(payload, documents);
		AnswerFeedbackResponse response = response();
		when(context.userId()).thenReturn(userId);
		when(resolver.resolveStrict(userId, payload.resumeId(), payload.jobDescriptionId())).thenReturn(documents);
		when(coach.scoreAnswer(input)).thenReturn(response);

		handler.handle(payload, context);

		InOrder order = inOrder(context, coach);
		order.verify(context).stage(JobStage.SCORING_ANSWER);
		order.verify(coach).scoreAnswer(input);
		order.verify(context).saveRootCheckpoint(response, "answer-feedback");
		order.verify(context).materializeFeedback(any(FeedbackPersistenceInput.class), org.mockito.ArgumentMatchers.eq(response));
	}

	@Test
	void retryReusesFeedbackCheckpoint() {
		AiResumeCoachService coach = mock(AiResumeCoachService.class);
		DocumentReferenceResolver resolver = mock(DocumentReferenceResolver.class);
		JobExecutionContext context = mock(JobExecutionContext.class);
		FeedbackJobHandler handler = new FeedbackJobHandler(coach, resolver);
		FeedbackJobPayload payload = payload();
		AnswerFeedbackResponse response = response();
		when(context.userId()).thenReturn(UUID.randomUUID());
		when(resolver.resolveStrict(context.userId(), payload.resumeId(), payload.jobDescriptionId()))
			.thenReturn(documents(payload));
		when(context.rootCheckpoint(AnswerFeedbackResponse.class, "score")).thenReturn(response);

		handler.handle(payload, context);

		verify(coach, never()).scoreAnswer(any(CoachFeedbackInput.class));
		verify(context).materializeFeedback(any(), org.mockito.ArgumentMatchers.eq(response));
	}

	private FeedbackJobPayload payload() {
		return new FeedbackJobPayload(
			UUID.randomUUID(), UUID.randomUUID(), "Backend", "Mid-level", "Question?",
			"System Design", List.of("Retries"), "Answer"
		);
	}

	private ResolvedJobInputs documents(FeedbackJobPayload payload) {
		return new ResolvedJobInputs(
			new ResolvedDocument(DocumentSourceType.RESUME, payload.resumeId(), "resume-hash", "resume", List.of()),
			Optional.of(new ResolvedDocument(
				DocumentSourceType.JOB_DESCRIPTION, payload.jobDescriptionId(), "jd-hash", "job", List.of()
			))
		);
	}

	private CoachFeedbackInput input(FeedbackJobPayload payload, ResolvedJobInputs documents) {
		return new CoachFeedbackInput(
			documents.resume(), documents.jobDescription(), payload.targetRole(), payload.seniority(),
			payload.questionText(), payload.category(), payload.expectedSignals(), payload.answerText()
		);
	}

	private AnswerFeedbackResponse response() {
		return new AnswerFeedbackResponse(
			88, "Strong", "Add details", List.of("Clear"), List.of("Metrics"),
			List.of("Outline"), "Follow-up?", "gemini", List.of("resume:experience:0")
		);
	}
}
