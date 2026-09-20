package dev.jiaming.ai_interview.coach

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.gemini.GeminiException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CoachResponseMapperTests {
	private val mapper = CoachResponseMapper(ObjectMapper())

	@Test
	fun rejectsQuestionResponsesWithoutUsableQuestions() {
		val response = InterviewQuestionsResponse(listOf(InterviewQuestionResponse("", "", "", "  ", emptyList(), emptyList())), "gemini")
		assertThatThrownBy { mapper.normalizeQuestions(response, listOf("resume:experience:0")) }
			.isInstanceOf(GeminiException::class.java)
			.hasMessage("Gemini returned no usable interview questions")
	}

	@Test
	fun normalizesAUsableQuestion() {
		val response = InterviewQuestionsResponse(listOf(InterviewQuestionResponse(null, "System Design", "deep", "How would you make this workflow idempotent?", listOf("Unique operation keys"), emptyList())), "Gemini")
		val normalized = mapper.normalizeQuestions(response, listOf("resume:experience:0"))
		assertThat(normalized.questions()).hasSize(1)
		assertThat(normalized.questions().first().difficulty()).isEqualTo("Deep Dive")
		assertThat(normalized.questions().first().sourceContextIds()).containsExactly("resume:experience:0")
	}

	@Test
	fun rejectsSourceContextIdsThatWereNotRetrieved() {
		val response = InterviewQuestionsResponse(listOf(InterviewQuestionResponse("question-id", "Projects", "Core", "How did you design this project?", listOf("Architecture decisions"), listOf("resume:4", "invented:source:9"))), "gemini")
		val normalized = mapper.normalizeQuestions(response, listOf("resume:projects:4", "resume:skills:5"))
		assertThat(normalized.questions().first().sourceContextIds()).containsExactly("resume:projects:4", "resume:skills:5")
	}
}
