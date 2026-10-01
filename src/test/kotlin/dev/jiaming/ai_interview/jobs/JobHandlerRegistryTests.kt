package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class JobHandlerRegistryTests {
	@Test
	fun requiresExactlyOneHandlerForEveryJobType() {
		val resume = handler(JobType.RESUME_EXTRACTION)
		val score = handler(JobType.RESUME_SCORE)
		val analysis = handler(JobType.ANALYSIS)
		val feedback = handler(JobType.ANSWER_FEEDBACK)
		val experienceSplit = handler(JobType.EXPERIENCE_SPLIT)
		val fit = handler(JobType.JOB_FIT)
		val registry = JobHandlerRegistry(listOf(resume, score, analysis, feedback, experienceSplit, fit))
		assertThat(registry.require(JobType.ANALYSIS)).isSameAs(analysis)
		assertThat(registry.require(JobType.RESUME_SCORE)).isSameAs(score)
		assertThat(registry.require(JobType.EXPERIENCE_SPLIT)).isSameAs(experienceSplit)
	}

	@Test
	fun rejectsDuplicateHandlers() {
		assertThatThrownBy {
			JobHandlerRegistry(listOf(handler(JobType.RESUME_EXTRACTION), handler(JobType.ANALYSIS), handler(JobType.ANALYSIS), handler(JobType.ANSWER_FEEDBACK), handler(JobType.EXPERIENCE_SPLIT)))
		}.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("Multiple job handlers")
	}

	@Test
	fun rejectsMissingHandlers() {
		assertThatThrownBy { JobHandlerRegistry(listOf(handler(JobType.ANALYSIS))) }
			.isInstanceOf(IllegalStateException::class.java)
			.hasMessageContaining("Missing job handlers")
	}

	private fun handler(type: JobType): JobHandler<Any> = object : JobHandler<Any> {
		override fun type() = type
		override fun payloadType() = Any::class.java
		override fun handle(payload: Any, context: JobExecutionContext): JsonNode? = null
	}
}
