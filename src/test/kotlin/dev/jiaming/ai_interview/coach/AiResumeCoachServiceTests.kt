package dev.jiaming.ai_interview.coach

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.document.DocumentChunk
import dev.jiaming.ai_interview.document.DocumentSourceType
import dev.jiaming.ai_interview.document.ResolvedDocument
import dev.jiaming.ai_interview.gemini.GeminiErrorCode
import dev.jiaming.ai_interview.gemini.GeminiException
import dev.jiaming.ai_interview.experience.ExperienceSplitItem
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.Optional
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito

class AiResumeCoachServiceTests {
	private val client = Mockito.mock(StructuredGenerationClient::class.java)
	private val contextService = Mockito.mock(CoachRagContextService::class.java)
	private val service = AiResumeCoachService(client, contextService, CoachPromptBuilder(), CoachResponseMapper(ObjectMapper()), SimpleMeterRegistry())

	@Test fun repairsInvalidJsonExactlyOnce() {
		val input = input()
		Mockito.`when`(contextService.assessmentContext(input)).thenReturn(CoachRagContext("direct", "context", listOf("resume:experience:0"), false))
		Mockito.`when`(client.generateJson(anyString())).thenReturn("not-json").thenReturn(assessmentJson())
		val response = service.assess(input)
		assertThat(response.overallScore).isEqualTo(80)
		assertThat(response.sourceContextIds).containsExactly("resume:experience:0")
		Mockito.verify(client, Mockito.times(2)).generateJson(anyString())
	}

	@Test fun rejectsSecondInvalidResponseWithoutThirdGeneration() {
		val input = input()
		Mockito.`when`(contextService.assessmentContext(input)).thenReturn(CoachRagContext("direct", "context", listOf("resume:experience:0"), false))
		Mockito.`when`(client.generateJson(anyString())).thenReturn("not-json")
		assertThatThrownBy { service.assess(input) }.isInstanceOfSatisfying(GeminiException::class.java) { exception ->
			assertThat(exception.code()).isEqualTo(GeminiErrorCode.INVALID_RESPONSE)
			assertThat(exception.retryable()).isFalse()
			assertThat(exception.message).doesNotContain("not-json")
		}
		Mockito.verify(client, Mockito.times(2)).generateJson(anyString())
	}

	@Test fun doesNotRepairMaxTokensFailure() {
		val input = input()
		Mockito.`when`(contextService.assessmentContext(input)).thenReturn(CoachRagContext("direct", "context", listOf("resume:experience:0"), false))
		Mockito.`when`(client.generateJson(anyString())).thenThrow(GeminiException(GeminiErrorCode.MAX_TOKENS, "token limit", false))
		assertThatThrownBy { service.assess(input) }.isInstanceOfSatisfying(GeminiException::class.java) { exception -> assertThat(exception.code()).isEqualTo(GeminiErrorCode.MAX_TOKENS) }
		Mockito.verify(client).generateJson(anyString())
	}

	@Test fun repairsExperienceOutputThatDoesNotMeetSaveableFieldRules() {
		val input = "Engineer\nBuilt durable services with PostgreSQL and measurable reliability improvements."
		Mockito.`when`(client.generateJson(anyString()))
			.thenReturn("""{"items":[{"title":"Engineer","organization":null,"startDate":"2023-1","endDate":null,"description":"Built a service."}]}""")
			.thenReturn("""{"items":[{"title":" Engineer ","organization":" Acme ","startDate":"2023-01","endDate":null,"description":" Built durable services. "}]}""")

		val result = service.splitExperience(input)

		assertThat(result.items).containsExactly(ExperienceSplitItem("Engineer", "Acme", "2023-01", null, "Built durable services.", null))
		Mockito.verify(client, Mockito.times(2)).generateJson(anyString())
	}

	@Test fun scoreResumeRepairsInvalidJsonOnceWithoutUsingRetrieval() {
		Mockito.`when`(client.generateJson(anyString())).thenReturn("not-json").thenReturn(scoreJson())

		val score = service.scoreResume("Built the payment API.", "Backend Engineer")

		assertThat(score.overall).isEqualTo(82)
		assertThat(score.jobTitle).isEqualTo("Backend Engineer")
		Mockito.verify(client, Mockito.times(2)).generateJson(anyString())
		Mockito.verifyNoInteractions(contextService)
	}

	@Test fun scoreResumeStopsAfterOneFailedRepairWithGeminiError() {
		Mockito.`when`(client.generateJson(anyString())).thenReturn("not-json")

		assertThatThrownBy { service.scoreResume("Built the payment API.", null) }
			.isInstanceOfSatisfying(GeminiException::class.java) { exception ->
				assertThat(exception.code()).isEqualTo(GeminiErrorCode.INVALID_RESPONSE)
				assertThat(exception.retryable()).isFalse()
			}
		Mockito.verify(client, Mockito.times(2)).generateJson(anyString())
		Mockito.verifyNoInteractions(contextService)
	}

	@Test fun practiceQuestionsBelowTheMinimumGoThroughTheSingleRepairAttempt() {
		val input = CoachAnalysisInput(input().resume(), Optional.empty(), null, null)
		Mockito.`when`(contextService.practiceQuestionContext(input)).thenReturn(CoachRagContext("direct", "context", listOf("resume:experience:0"), false))
		Mockito.`when`(client.generateJson(anyString())).thenReturn(practiceJson(2)).thenReturn(practiceJson(4))

		val drafts = service.generatePracticeQuestions(input).drafts

		assertThat(drafts).hasSize(4)
		val prompts = org.mockito.kotlin.argumentCaptor<String>()
		Mockito.verify(client, Mockito.times(2)).generateJson(prompts.capture())
		assertThat(prompts.allValues[1]).contains("Repair the JSON response", "at least 3")
		Mockito.verify(contextService, Mockito.never()).questionContext(input)
	}

	private fun practiceJson(count: Int) = (1..count).joinToString(",", """{"questions":[""", "]}") {
		"""{"category":"Depth","questionText":"Question $it?","rationale":"Reason $it","expectedSignals":["signal"]}"""
	}

	private fun input(): CoachAnalysisInput {
		val resume = ResolvedDocument(DocumentSourceType.RESUME, UUID.randomUUID(), "hash", "EXPERIENCE\nBuilt APIs", listOf(DocumentChunk(0, "Experience", "Built APIs", "resume:experience:0")))
		return CoachAnalysisInput(resume, Optional.empty(), "Backend Engineer", "Mid-level")
	}

	private fun assessmentJson() = """{"overallScore":80,"scores":{"technicalDepth":80,"impact":80,"clarity":80,"relevance":80,"ats":80},"strengths":["Clear impact"],"weaknesses":["More scale detail needed"],"recommendations":[{"section":"Experience","priority":"high","message":"Add scale"}],"sourceContextIds":["resume:experience:0"]}"""
	private fun scoreJson() = """{"overall":82,"scores":{"technicalDepth":82,"impact":80,"clarity":84,"relevance":81,"ats":83},"summary":"Strong backend work.","fixes":[{"section":"Experience","priority":"HIGH","message":"Add scope."}],"rewrites":[{"section":"Experience","original":"Built API.","rewritten":"Built API serving [N] services."}]}"""
}
