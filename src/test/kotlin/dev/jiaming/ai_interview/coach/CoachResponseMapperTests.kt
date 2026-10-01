package dev.jiaming.ai_interview.coach

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.gemini.GeminiException
import dev.jiaming.ai_interview.experience.ExperienceSplitResult
import dev.jiaming.ai_interview.experience.ExperienceSplitItem
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
		val questions = requireNotNull(normalized.questions)
		assertThat(questions).hasSize(1)
		assertThat(questions.first().difficulty).isEqualTo("Deep Dive")
		assertThat(questions.first().sourceContextIds).containsExactly("resume:experience:0")
	}

	@Test
	fun rejectsSourceContextIdsThatWereNotRetrieved() {
		val response = InterviewQuestionsResponse(listOf(InterviewQuestionResponse("question-id", "Projects", "Core", "How did you design this project?", listOf("Architecture decisions"), listOf("resume:4", "invented:source:9"))), "gemini")
		val normalized = mapper.normalizeQuestions(response, listOf("resume:projects:4", "resume:skills:5"))
		assertThat(requireNotNull(normalized.questions).first().sourceContextIds).containsExactly("resume:projects:4", "resume:skills:5")
	}

	@Test
	fun normalizesExperienceSplitFieldsAndLeavesDuplicateChecksForTheOwnerScopedService() {
		val response = ExperienceSplitResponse(listOf(
			ExperienceSplitResponseItem("  Senior   Engineer ", " Acme ", "2021-03", null, " Built a reliable service. ")
		))

		val result: ExperienceSplitResult = mapper.normalizeExperienceSplit(response)

		assertThat(result.items).containsExactly(ExperienceSplitItem("Senior Engineer", "Acme", "2021-03", null,
			"Built a reliable service.", null))
	}

	@Test
	fun rejectsExperienceSplitItemsThatCannotBeSaved() {
		val response = ExperienceSplitResponse(listOf(
			ExperienceSplitResponseItem("Engineer", null, "2023-1", null, "Built a service.")
		))

		assertThatThrownBy { mapper.normalizeExperienceSplit(response) }
			.isInstanceOf(GeminiException::class.java)
			.hasMessageContaining("experience")
	}

    @Test
    fun normalizesResumeScoresFixRanksPrioritiesAndRewritePlaceholders() {
        val draft = ResumeScoreDraftResponse(
            130,
            AssessmentScores(130, -4, 64, 75, 71),
            "Strong backend depth; impact is under-quantified.",
            listOf(
                ResumeScoreFixDraft("Experience", "high", "Quantify the result."),
                ResumeScoreFixDraft("Skills", "invalid", "Group related tools.")
            ),
            listOf(ResumeScoreRewriteDraft("Experience", "Improved latency.", "Cut latency by [X%] across [N] services."))
        )

        val normalized = mapper.normalizeResumeScore(draft, "Backend Engineer")

        assertThat(normalized.overall).isEqualTo(100)
        assertThat(normalized.scores.technicalDepth).isEqualTo(100)
        assertThat(normalized.scores.impact).isZero()
        assertThat(normalized.fixes.map { it.rank }).containsExactly(1, 2)
        assertThat(normalized.fixes.map { it.priority }).containsExactly("HIGH", "MEDIUM")
        assertThat(normalized.rewrites.single().placeholders).containsExactly("[X%]", "[N]")
        assertThat(normalized.jobTitle).isEqualTo("Backend Engineer")
    }
}
