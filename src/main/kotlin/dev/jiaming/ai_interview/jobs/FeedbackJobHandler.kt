package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.stereotype.Component
import dev.jiaming.ai_interview.coach.AiResumeCoachService
import dev.jiaming.ai_interview.coach.AnswerFeedbackResponse
import dev.jiaming.ai_interview.coach.CoachFeedbackInput
import dev.jiaming.ai_interview.document.DocumentReferenceResolver
import dev.jiaming.ai_interview.interview.FeedbackPersistenceInput

@Component
class FeedbackJobHandler(
    private val coachService: AiResumeCoachService,
    private val documentResolver: DocumentReferenceResolver
) : JobHandler<FeedbackJobPayload> {
    override fun type() = JobType.ANSWER_FEEDBACK
    override fun payloadType() = FeedbackJobPayload::class.java

    override fun handle(payload: FeedbackJobPayload, context: JobExecutionContext): JsonNode {
        context.stage(JobStage.SCORING_ANSWER)
        val documents = documentResolver.resolveStrict(context.userId(), payload.resumeId, payload.jobDescriptionId)
        val input = CoachFeedbackInput(documents.resume(), documents.jobDescription(), payload.targetRole, payload.seniority,
            payload.questionText, payload.category, payload.expectedSignals(), payload.answerText)
        val persistenceInput = FeedbackPersistenceInput.from(context.userId(), input)
        var feedback = context.rootCheckpoint(AnswerFeedbackResponse::class.java, "score")
        if (feedback == null) {
            feedback = coachService.scoreAnswer(input)
            context.saveRootCheckpoint(feedback, "answer-feedback")
        }
        context.materializeFeedback(persistenceInput, feedback)
        return context.toJson(feedback)
    }
}
