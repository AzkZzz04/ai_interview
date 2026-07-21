package dev.jiaming.ai_interview.coach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import dev.jiaming.ai_interview.document.DocumentChunk;
import dev.jiaming.ai_interview.document.DocumentSourceType;
import dev.jiaming.ai_interview.document.ResolvedDocument;
import dev.jiaming.ai_interview.gemini.GeminiErrorCode;
import dev.jiaming.ai_interview.gemini.GeminiException;

class AiResumeCoachServiceTests {

	private final StructuredGenerationClient client = mock(StructuredGenerationClient.class);

	private final CoachRagContextService contextService = mock(CoachRagContextService.class);

	private final CoachPromptBuilder promptBuilder = new CoachPromptBuilder();

	private final AiResumeCoachService service = new AiResumeCoachService(
		client,
		contextService,
		promptBuilder,
		new CoachResponseMapper(new ObjectMapper()),
		new SimpleMeterRegistry()
	);

	@Test
	void repairsInvalidJsonExactlyOnce() {
		CoachAnalysisInput input = input();
		when(contextService.assessmentContext(input))
			.thenReturn(new CoachRagContext("direct", "context", List.of("resume:experience:0"), false));
		when(client.generateJson(org.mockito.ArgumentMatchers.anyString()))
			.thenReturn("not-json")
			.thenReturn(assessmentJson());

		AssessmentResponse response = service.assess(input);

		assertThat(response.overallScore()).isEqualTo(80);
		assertThat(response.sourceContextIds()).containsExactly("resume:experience:0");
		verify(client, org.mockito.Mockito.times(2)).generateJson(org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void rejectsSecondInvalidResponseWithoutThirdGeneration() {
		CoachAnalysisInput input = input();
		when(contextService.assessmentContext(input))
			.thenReturn(new CoachRagContext("direct", "context", List.of("resume:experience:0"), false));
		when(client.generateJson(org.mockito.ArgumentMatchers.anyString())).thenReturn("not-json");

		assertThatThrownBy(() -> service.assess(input))
			.isInstanceOfSatisfying(GeminiException.class, exception -> {
				assertThat(exception.code()).isEqualTo(GeminiErrorCode.INVALID_RESPONSE);
				assertThat(exception.retryable()).isFalse();
				assertThat(exception.getMessage()).doesNotContain("not-json");
			});
		verify(client, org.mockito.Mockito.times(2)).generateJson(org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void doesNotRepairMaxTokensFailure() {
		CoachAnalysisInput input = input();
		when(contextService.assessmentContext(input))
			.thenReturn(new CoachRagContext("direct", "context", List.of("resume:experience:0"), false));
		when(client.generateJson(org.mockito.ArgumentMatchers.anyString())).thenThrow(
			new GeminiException(GeminiErrorCode.MAX_TOKENS, "token limit", false)
		);

		assertThatThrownBy(() -> service.assess(input))
			.isInstanceOfSatisfying(GeminiException.class, exception ->
				assertThat(exception.code()).isEqualTo(GeminiErrorCode.MAX_TOKENS));
		verify(client).generateJson(org.mockito.ArgumentMatchers.anyString());
	}

	private CoachAnalysisInput input() {
		ResolvedDocument resume = new ResolvedDocument(
			DocumentSourceType.RESUME,
			UUID.randomUUID(),
			"hash",
			"EXPERIENCE\nBuilt APIs",
			List.of(new DocumentChunk(0, "Experience", "Built APIs", "resume:experience:0"))
		);
		return new CoachAnalysisInput(resume, Optional.empty(), "Backend Engineer", "Mid-level");
	}

	private String assessmentJson() {
		return """
			{
			  "overallScore": 80,
			  "scores": {"technicalDepth":80,"impact":80,"clarity":80,"relevance":80,"ats":80},
			  "strengths": ["Clear impact"],
			  "weaknesses": ["More scale detail needed"],
			  "recommendations": [{"section":"Experience","priority":"high","message":"Add scale"}],
			  "sourceContextIds": ["resume:experience:0"]
			}
			""";
	}
}
