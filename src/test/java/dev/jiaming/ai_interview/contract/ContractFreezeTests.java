package dev.jiaming.ai_interview.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import dev.jiaming.ai_interview.coach.AssessmentScores;
import dev.jiaming.ai_interview.gemini.GeminiErrorCode;
import dev.jiaming.ai_interview.jobs.JobStage;
import dev.jiaming.ai_interview.jobs.JobStatus;

/**
 * Freezes the enum and DTO surfaces the Next.js client hard-codes. The frontend keys UI
 * behaviour on exact {@code JobStatus}/{@code JobStage} strings, renders assessment scores
 * via {@code Object.keys(scores)}, and maps errors by provider {@code code}. Any additive
 * or renaming change to these must be a deliberate, coordinated contract change - and must
 * break this test first.
 */
class ContractFreezeTests {

	@Test
	void jobStatusValuesAreFrozen() {
		assertThat(names(JobStatus.values()))
			.containsExactly("QUEUED", "PROCESSING", "RETRYING", "SUCCEEDED", "PARTIAL", "FAILED");
	}

	@Test
	void jobStageValuesAreFrozen() {
		assertThat(names(JobStage.values()))
			.containsExactly(
				"QUEUED", "READING_FILE", "EXTRACTING_TEXT", "NORMALIZING_TEXT", "CHUNKING_TEXT",
				"ASSESSING_RESUME", "GENERATING_QUESTIONS", "SCORING_ANSWER", "COMPLETED"
			);
	}

	@Test
	void assessmentScoreKeysAreFrozen() {
		String[] components = Arrays.stream(AssessmentScores.class.getRecordComponents())
			.map(RecordComponent::getName)
			.toArray(String[]::new);

		assertThat(components).containsExactly("technicalDepth", "impact", "clarity", "relevance", "ats");
	}

	@Test
	void geminiErrorCodesTheClientMapsAreFrozen() {
		assertThat(GeminiErrorCode.NOT_CONFIGURED).isEqualTo("GEMINI_NOT_CONFIGURED");
		assertThat(GeminiErrorCode.RATE_LIMITED).isEqualTo("GEMINI_RATE_LIMITED");
		assertThat(GeminiErrorCode.TIMEOUT).isEqualTo("GEMINI_TIMEOUT");
		assertThat(GeminiErrorCode.UPSTREAM_ERROR).isEqualTo("GEMINI_UPSTREAM_ERROR");
		assertThat(GeminiErrorCode.SAFETY).isEqualTo("GEMINI_SAFETY");
		assertThat(GeminiErrorCode.RECITATION).isEqualTo("GEMINI_RECITATION");
		assertThat(GeminiErrorCode.MAX_TOKENS).isEqualTo("GEMINI_MAX_TOKENS");
		assertThat(GeminiErrorCode.EMPTY_RESPONSE).isEqualTo("GEMINI_EMPTY_RESPONSE");
		assertThat(GeminiErrorCode.INVALID_RESPONSE).isEqualTo("GEMINI_INVALID_RESPONSE");
	}

	private static String[] names(Enum<?>[] values) {
		return Arrays.stream(values).map(Enum::name).toArray(String[]::new);
	}
}
