package dev.jiaming.ai_interview.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import dev.jiaming.ai_interview.coach.AiResumeCoachService;
import dev.jiaming.ai_interview.coach.AssessmentResponse;
import dev.jiaming.ai_interview.coach.CoachAnalysisInput;
import dev.jiaming.ai_interview.coach.InterviewQuestionsResponse;
import dev.jiaming.ai_interview.document.DocumentReferenceResolver;
import dev.jiaming.ai_interview.document.ResolvedJobInputs;
import dev.jiaming.ai_interview.interview.AnalysisPersistenceInput;

@Component
public class AnalysisJobHandler implements JobHandler<AnalysisJobPayload> {

	private final AiResumeCoachService coachService;

	private final DocumentReferenceResolver documentResolver;

	public AnalysisJobHandler(
		AiResumeCoachService coachService,
		DocumentReferenceResolver documentResolver
	) {
		this.coachService = coachService;
		this.documentResolver = documentResolver;
	}

	@Override
	public JobType type() {
		return JobType.ANALYSIS;
	}

	@Override
	public Class<AnalysisJobPayload> payloadType() {
		return AnalysisJobPayload.class;
	}

	@Override
	public JsonNode handle(AnalysisJobPayload payload, JobExecutionContext context) {
		ResolvedJobInputs documents = documentResolver.resolveStrict(
			context.userId(), payload.resumeId(), payload.jobDescriptionId()
		);
		CoachAnalysisInput coachInput = new CoachAnalysisInput(
			documents.resume(),
			documents.jobDescription(),
			payload.targetRole(),
			payload.seniority()
		);
		AnalysisPersistenceInput persistenceInput = AnalysisPersistenceInput.from(
			context.userId(), coachInput
		);

		context.stage(JobStage.ASSESSING_RESUME);
		AssessmentResponse assessment = context.checkpoint("assessment", AssessmentResponse.class);
		if (assessment == null) {
			assessment = coachService.assess(coachInput);
			context.saveCheckpoint("assessment", assessment);
		}
		context.materializeAssessment(persistenceInput, assessment);

		context.stage(JobStage.GENERATING_QUESTIONS);
		InterviewQuestionsResponse questions = context.checkpoint("questions", InterviewQuestionsResponse.class);
		if (questions == null) {
			questions = coachService.generateQuestions(coachInput);
			context.saveCheckpoint("questions", questions);
		}
		context.materializeQuestions(persistenceInput, questions);
		return context.toJson(new AnalysisJobResult(assessment, questions));
	}
}
