package dev.jiaming.ai_interview.jobs

import dev.jiaming.ai_interview.coach.AiResumeCoachService
import dev.jiaming.ai_interview.coach.AnswerFeedbackResponse
import dev.jiaming.ai_interview.coach.CoachFeedbackInput
import dev.jiaming.ai_interview.document.DocumentReferenceResolver
import dev.jiaming.ai_interview.document.DocumentSourceType
import dev.jiaming.ai_interview.document.ResolvedDocument
import dev.jiaming.ai_interview.document.ResolvedJobInputs
import dev.jiaming.ai_interview.interview.FeedbackPersistenceInput
import java.util.Optional
import java.util.UUID
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito

class FeedbackJobHandlerTests {
	@Test
	fun checkpointsFeedbackBeforeMaterializingIt() {
		val coach = Mockito.mock(AiResumeCoachService::class.java)
		val resolver = Mockito.mock(DocumentReferenceResolver::class.java)
		val context = Mockito.mock(JobExecutionContext::class.java)
		val handler = FeedbackJobHandler(coach, resolver)
		val userId = UUID.randomUUID()
		val payload = payload()
		val documents = documents(payload)
		val input = input(payload, documents)
		val response = response()
		Mockito.`when`(context.userId()).thenReturn(userId)
		Mockito.`when`(resolver.resolveStrict(userId, payload.resumeId(), payload.jobDescriptionId())).thenReturn(documents)
		Mockito.`when`(coach.scoreAnswer(input)).thenReturn(response)

		handler.handle(payload, context)

		val order = Mockito.inOrder(context, coach)
		order.verify(context).stage(JobStage.SCORING_ANSWER)
		order.verify(coach).scoreAnswer(input)
		order.verify(context).saveRootCheckpoint(response, "answer-feedback")
		order.verify(context).materializeFeedback(any(FeedbackPersistenceInput::class.java), eq(response))
	}

	@Test
	fun retryReusesFeedbackCheckpoint() {
		val coach = Mockito.mock(AiResumeCoachService::class.java)
		val resolver = Mockito.mock(DocumentReferenceResolver::class.java)
		val context = Mockito.mock(JobExecutionContext::class.java)
		val handler = FeedbackJobHandler(coach, resolver)
		val payload = payload()
		val response = response()
		Mockito.`when`(context.userId()).thenReturn(UUID.randomUUID())
		Mockito.`when`(resolver.resolveStrict(context.userId(), payload.resumeId(), payload.jobDescriptionId())).thenReturn(documents(payload))
		Mockito.`when`(context.rootCheckpoint(AnswerFeedbackResponse::class.java, "score")).thenReturn(response)

		handler.handle(payload, context)

		Mockito.verify(coach, Mockito.never()).scoreAnswer(any(CoachFeedbackInput::class.java))
		Mockito.verify(context).materializeFeedback(any(), eq(response))
	}

	private fun payload() = FeedbackJobPayload(UUID.randomUUID(), UUID.randomUUID(), "Backend", "Mid-level", "Question?", "System Design", listOf("Retries"), "Answer")
	private fun documents(payload: FeedbackJobPayload) = ResolvedJobInputs(ResolvedDocument(DocumentSourceType.RESUME, payload.resumeId(), "resume-hash", "resume", emptyList()), Optional.of(ResolvedDocument(DocumentSourceType.JOB_DESCRIPTION, payload.jobDescriptionId(), "jd-hash", "job", emptyList())))
	private fun input(payload: FeedbackJobPayload, documents: ResolvedJobInputs) = CoachFeedbackInput(documents.resume(), documents.jobDescription(), payload.targetRole(), payload.seniority(), payload.questionText(), payload.category(), payload.expectedSignals(), payload.answerText())
	private fun response() = AnswerFeedbackResponse(88, "Strong", "Add details", listOf("Clear"), listOf("Metrics"), listOf("Outline"), "Follow-up?", "gemini", listOf("resume:experience:0"))
}
