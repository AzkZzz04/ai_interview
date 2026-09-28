package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.stereotype.Component
import dev.jiaming.ai_interview.coach.AiResumeCoachService
import dev.jiaming.ai_interview.coach.AssessmentResponse
import dev.jiaming.ai_interview.coach.CoachAnalysisInput
import dev.jiaming.ai_interview.coach.InterviewQuestionsResponse
import dev.jiaming.ai_interview.document.DocumentReferenceResolver
import dev.jiaming.ai_interview.interview.AnalysisPersistenceInput

@Component
class AnalysisJobHandler(
    private val coachService: AiResumeCoachService,
    private val documentResolver: DocumentReferenceResolver
) : JobHandler<AnalysisJobPayload> {
    override fun type() = JobType.ANALYSIS
    override fun payloadType() = AnalysisJobPayload::class.java

    override fun handle(payload: AnalysisJobPayload, context: JobExecutionContext): JsonNode {
        val documents = documentResolver.resolveStrict(context.userId(), payload.resumeId, payload.jobDescriptionId)
        val input = CoachAnalysisInput(documents.resume(), documents.jobDescription(), payload.targetRole, payload.seniority)
        val persistenceInput = AnalysisPersistenceInput.from(context.userId(), input)
        context.stage(JobStage.ASSESSING_RESUME)
        var assessment = context.checkpoint("assessment", AssessmentResponse::class.java)
        if (assessment == null) {
            assessment = coachService.assess(input)
            context.saveCheckpoint("assessment", assessment)
        }
        context.materializeAssessment(persistenceInput, assessment)
        context.stage(JobStage.GENERATING_QUESTIONS)
        var questions = context.checkpoint("questions", InterviewQuestionsResponse::class.java)
        if (questions == null) {
            questions = coachService.generateQuestions(input)
            context.saveCheckpoint("questions", questions)
        }
        context.materializeQuestions(persistenceInput, questions)
        return context.toJson(AnalysisJobResult(assessment, questions))
    }
}
