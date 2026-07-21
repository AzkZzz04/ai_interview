package dev.jiaming.ai_interview.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import dev.jiaming.ai_interview.coach.AiResumeCoachService;
import dev.jiaming.ai_interview.coach.AnswerFeedbackResponse;
import dev.jiaming.ai_interview.coach.CoachFeedbackInput;
import dev.jiaming.ai_interview.document.DocumentReferenceResolver;
import dev.jiaming.ai_interview.document.ResolvedJobInputs;
import dev.jiaming.ai_interview.interview.FeedbackPersistenceInput;

@Component
public class FeedbackJobHandler implements JobHandler<FeedbackJobPayload> {

	private final AiResumeCoachService coachService;

	private final DocumentReferenceResolver documentResolver;

	public FeedbackJobHandler(
		AiResumeCoachService coachService,
		DocumentReferenceResolver documentResolver
	) {
		this.coachService = coachService;
		this.documentResolver = documentResolver;
	}

	@Override
	public JobType type() {
		return JobType.ANSWER_FEEDBACK;
	}

	@Override
	public Class<FeedbackJobPayload> payloadType() {
		return FeedbackJobPayload.class;
	}

	@Override
	public JsonNode handle(FeedbackJobPayload payload, JobExecutionContext context) {
		context.stage(JobStage.SCORING_ANSWER);
		ResolvedJobInputs documents = documentResolver.resolveStrict(
			context.userId(), payload.resumeId(), payload.jobDescriptionId()
		);
		CoachFeedbackInput coachInput = new CoachFeedbackInput(
			documents.resume(),
			documents.jobDescription(),
			payload.targetRole(),
			payload.seniority(),
			payload.questionText(),
			payload.category(),
			payload.expectedSignals(),
			payload.answerText()
		);
		FeedbackPersistenceInput persistenceInput = FeedbackPersistenceInput.from(
			context.userId(), coachInput
		);

		AnswerFeedbackResponse feedback = context.rootCheckpoint(AnswerFeedbackResponse.class, "score");
		if (feedback == null) {
			feedback = coachService.scoreAnswer(coachInput);
			context.saveRootCheckpoint(feedback, "answer-feedback");
		}
		context.materializeFeedback(persistenceInput, feedback);
		return context.toJson(feedback);
	}
}
