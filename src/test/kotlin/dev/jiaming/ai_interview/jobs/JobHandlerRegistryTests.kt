package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class JobHandlerRegistryTests {
	@Test
	fun requiresExactlyOneHandlerForEveryJobType() {
		val resume = handler(JobType.RESUME_EXTRACTION)
		val analysis = handler(JobType.ANALYSIS)
		val feedback = handler(JobType.ANSWER_FEEDBACK)
		val registry = JobHandlerRegistry(listOf(resume, analysis, feedback))
		assertThat(registry.require(JobType.ANALYSIS)).isSameAs(analysis)
	}

	@Test
	fun rejectsDuplicateHandlers() {
		assertThatThrownBy {
			JobHandlerRegistry(listOf(handler(JobType.RESUME_EXTRACTION), handler(JobType.ANALYSIS), handler(JobType.ANALYSIS), handler(JobType.ANSWER_FEEDBACK)))
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
