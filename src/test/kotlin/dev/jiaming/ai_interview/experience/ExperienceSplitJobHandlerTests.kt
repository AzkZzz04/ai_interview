package dev.jiaming.ai_interview.experience

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.coach.AiResumeCoachService
import dev.jiaming.ai_interview.jobs.JobExecutionContext
import dev.jiaming.ai_interview.jobs.JobStage
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq

class ExperienceSplitJobHandlerTests {
	private val objectMapper = ObjectMapper().findAndRegisterModules()
	private val coach = Mockito.mock(AiResumeCoachService::class.java)
	private val experienceService = Mockito.mock(ExperienceService::class.java)
	private val context = Mockito.mock(JobExecutionContext::class.java)
	private val handler = ExperienceSplitJobHandler(coach, experienceService)

	@Test
	fun returnsReviewOnlyItemsWithOwnerScopedDuplicateAnnotations() {
		val userId = UUID.randomUUID()
		val payload = ExperienceSplitJobPayload(text = "LinkedIn experience text long enough for the extraction step")
		val generated = ExperienceSplitResult(listOf(item("Engineer")))
		val saved = item("Engineer", ExperienceDuplicate(UUID.randomUUID(), "Engineer at Acme"))
		val reviewed = ExperienceSplitResult(listOf(saved))
		Mockito.`when`(context.userId()).thenReturn(userId)
		Mockito.`when`(context.checkpoint("result", ExperienceSplitResult::class.java)).thenReturn(null)
		Mockito.`when`(context.checkpoint("split", ExperienceSplitResult::class.java)).thenReturn(null)
		Mockito.`when`(coach.splitExperience(payload.text)).thenReturn(generated)
		Mockito.`when`(experienceService.annotateDuplicates(userId, generated)).thenReturn(reviewed)
		Mockito.`when`(context.toJson(reviewed)).thenReturn(objectMapper.valueToTree(reviewed))

		val result = handler.handle(payload, context)

		assertThat(result).isEqualTo(objectMapper.valueToTree(reviewed))
		Mockito.verify(context).stage(JobStage.SPLITTING_EXPERIENCE)
		Mockito.verify(context).saveCheckpoint("result", reviewed)
		Mockito.verify(context).saveCheckpoint("split", generated)
		Mockito.verify(context, Mockito.never()).materializeAssessment(any(), any())
		Mockito.verify(context, Mockito.never()).materializeQuestions(any(), any())
		Mockito.verify(context, Mockito.never()).materializeFeedback(any(), any())
	}

	@Test
	fun retryWithSavedAiResponseSkipsAnotherModelCall() {
		val payload = ExperienceSplitJobPayload(text = "LinkedIn experience text long enough for the extraction step")
		val generated = ExperienceSplitResult(listOf(item("Engineer")))
		val reviewed = ExperienceSplitResult(listOf(item("Engineer", ExperienceDuplicate(UUID.randomUUID(), "Engineer"))))
		Mockito.`when`(context.userId()).thenReturn(UUID.randomUUID())
		Mockito.`when`(context.checkpoint("result", ExperienceSplitResult::class.java)).thenReturn(null)
		Mockito.`when`(context.checkpoint("split", ExperienceSplitResult::class.java)).thenReturn(generated)
		Mockito.`when`(experienceService.annotateDuplicates(any(), eq(generated))).thenReturn(reviewed)
		Mockito.`when`(context.toJson(reviewed)).thenReturn(objectMapper.valueToTree(reviewed))

		handler.handle(payload, context)

		Mockito.verifyNoInteractions(coach)
		Mockito.verify(context, Mockito.never()).saveCheckpoint("split", generated)
	}

	@Test
	fun checkpointedReviewResultSkipsAnotherAiCall() {
		val userId = UUID.randomUUID()
		val payload = ExperienceSplitJobPayload(text = "LinkedIn experience text long enough for the extraction step")
		val result = ExperienceSplitResult(listOf(item("Engineer")))
		Mockito.`when`(context.userId()).thenReturn(userId)
		Mockito.`when`(context.checkpoint("result", ExperienceSplitResult::class.java)).thenReturn(result)
		Mockito.`when`(context.toJson(result)).thenReturn(objectMapper.valueToTree(result))

		assertThat(handler.handle(payload, context)).isEqualTo(objectMapper.valueToTree(result))
		Mockito.verifyNoInteractions(coach)
		Mockito.verifyNoInteractions(experienceService)
	}

	private fun item(title: String, duplicate: ExperienceDuplicate? = null) = ExperienceSplitItem(
		title, "Acme", "2023-01", null, "Built a dependable service.", duplicate
	)
}
