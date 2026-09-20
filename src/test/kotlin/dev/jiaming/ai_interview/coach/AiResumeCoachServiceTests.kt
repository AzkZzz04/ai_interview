package dev.jiaming.ai_interview.coach

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.document.DocumentChunk
import dev.jiaming.ai_interview.document.DocumentSourceType
import dev.jiaming.ai_interview.document.ResolvedDocument
import dev.jiaming.ai_interview.gemini.GeminiErrorCode
import dev.jiaming.ai_interview.gemini.GeminiException
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
		assertThat(response.overallScore()).isEqualTo(80)
		assertThat(response.sourceContextIds()).containsExactly("resume:experience:0")
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

	private fun input(): CoachAnalysisInput {
		val resume = ResolvedDocument(DocumentSourceType.RESUME, UUID.randomUUID(), "hash", "EXPERIENCE\nBuilt APIs", listOf(DocumentChunk(0, "Experience", "Built APIs", "resume:experience:0")))
		return CoachAnalysisInput(resume, Optional.empty(), "Backend Engineer", "Mid-level")
	}

	private fun assessmentJson() = """{"overallScore":80,"scores":{"technicalDepth":80,"impact":80,"clarity":80,"relevance":80,"ats":80},"strengths":["Clear impact"],"weaknesses":["More scale detail needed"],"recommendations":[{"section":"Experience","priority":"high","message":"Add scale"}],"sourceContextIds":["resume:experience:0"]}"""
}
