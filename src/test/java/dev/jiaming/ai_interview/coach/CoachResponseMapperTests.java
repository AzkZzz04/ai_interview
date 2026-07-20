package dev.jiaming.ai_interview.coach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import dev.jiaming.ai_interview.gemini.GeminiException;

class CoachResponseMapperTests {

	private final CoachResponseMapper mapper = new CoachResponseMapper(new ObjectMapper());

	@Test
	void rejectsQuestionResponsesWithoutUsableQuestions() {
		InterviewQuestionsResponse response = new InterviewQuestionsResponse(
			List.of(new InterviewQuestionResponse("", "", "", "  ", List.of(), List.of())),
			"gemini"
		);

		assertThatThrownBy(() -> mapper.normalizeQuestions(response, List.of("resume:experience:0")))
			.isInstanceOf(GeminiException.class)
			.hasMessage("Gemini returned no usable interview questions");
	}

	@Test
	void normalizesAUsableQuestion() {
		InterviewQuestionsResponse response = new InterviewQuestionsResponse(
			List.of(new InterviewQuestionResponse(
				null,
				"System Design",
				"deep",
				"How would you make this workflow idempotent?",
				List.of("Unique operation keys"),
				List.of()
			)),
			"Gemini"
		);

		InterviewQuestionsResponse normalized = mapper.normalizeQuestions(response, List.of("resume:experience:0"));

		assertThat(normalized.questions()).hasSize(1);
		assertThat(normalized.questions().getFirst().difficulty()).isEqualTo("Deep Dive");
		assertThat(normalized.questions().getFirst().sourceContextIds()).containsExactly("resume:experience:0");
	}

	@Test
	void rejectsSourceContextIdsThatWereNotRetrieved() {
		InterviewQuestionsResponse response = new InterviewQuestionsResponse(
			List.of(new InterviewQuestionResponse(
				"question-id",
				"Projects",
				"Core",
				"How did you design this project?",
				List.of("Architecture decisions"),
				List.of("resume:4", "invented:source:9")
			)),
			"gemini"
		);

		InterviewQuestionsResponse normalized = mapper.normalizeQuestions(
			response,
			List.of("resume:projects:4", "resume:skills:5")
		);

		assertThat(normalized.questions().getFirst().sourceContextIds())
			.containsExactly("resume:projects:4", "resume:skills:5");
	}
}
