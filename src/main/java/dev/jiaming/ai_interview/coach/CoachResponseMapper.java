package dev.jiaming.ai_interview.coach;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import dev.jiaming.ai_interview.gemini.GeminiException;
import dev.jiaming.ai_interview.gemini.GeminiErrorCode;

@Component
public class CoachResponseMapper {

	private final ObjectMapper objectMapper;

	public CoachResponseMapper(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public <T> T parse(String json, Class<T> responseType) {
		try {
			return objectMapper.readValue(json, responseType);
		}
		catch (IOException exception) {
			throw new GeminiException(
				GeminiErrorCode.INVALID_RESPONSE,
				"Gemini returned JSON that did not match the expected AI contract",
				exception,
				false
			);
		}
	}

	public AssessmentResponse normalizeAssessment(AssessmentResponse response, List<String> fallbackSourceContextIds) {
		AssessmentScores scores = response.scores() == null
			? new AssessmentScores(0, 0, 0, 0, 0)
			: response.scores();
		AssessmentScores normalizedScores = new AssessmentScores(
			clampScore(scores.technicalDepth()),
			clampScore(scores.impact()),
			clampScore(scores.clarity()),
			clampScore(scores.relevance()),
			clampScore(scores.ats())
		);
		int overallScore = response.overallScore() > 0
			? clampScore(response.overallScore())
			: average(normalizedScores);
		return new AssessmentResponse(
			overallScore,
			normalizedScores,
			nonEmpty(response.strengths()),
			nonEmpty(response.weaknesses()),
			nonEmptyRecommendations(response.recommendations()),
			"gemini",
			sourceContextIds(response.sourceContextIds(), fallbackSourceContextIds)
		);
	}

	public InterviewQuestionsResponse normalizeQuestions(
		InterviewQuestionsResponse response,
		List<String> fallbackSourceContextIds
	) {
		List<InterviewQuestionResponse> questions = safeList(response.questions()).stream()
			.filter(question -> !blank(question.questionText()))
			.limit(12)
			.map(question -> new InterviewQuestionResponse(
				fallback(question.id(), slug(question.category() + "-" + question.questionText())),
				fallback(question.category(), "Interview"),
				normalizeDifficulty(question.difficulty()),
				question.questionText(),
				nonEmpty(question.expectedSignals()),
				sourceContextIds(question.sourceContextIds(), fallbackSourceContextIds)
			))
			.toList();
		if (questions.isEmpty()) {
			throw new GeminiException(
				GeminiErrorCode.INVALID_RESPONSE,
				"Gemini returned no usable interview questions",
				false
			);
		}
		return new InterviewQuestionsResponse(questions, "gemini");
	}

	public AnswerFeedbackResponse normalizeFeedback(
		AnswerFeedbackResponse response,
		List<String> fallbackSourceContextIds
	) {
		return new AnswerFeedbackResponse(
			clampScore(response.score()),
			fallback(response.summary(), "The answer was scored, but Gemini did not provide a summary."),
			fallback(response.nextStep(), "Add clearer structure, technical detail, and measurable outcomes."),
			nonEmpty(response.strengths()),
			nonEmpty(response.gaps()),
			nonEmpty(response.betterAnswerOutline()),
			fallback(response.followUpQuestion(), ""),
			"gemini",
			sourceContextIds(response.sourceContextIds(), fallbackSourceContextIds)
		);
	}

	private List<String> nonEmpty(List<String> values) {
		List<String> cleaned = safeList(values).stream()
			.filter(Objects::nonNull)
			.map(String::trim)
			.filter(value -> !value.isBlank())
			.limit(6)
			.toList();
		return cleaned.isEmpty() ? List.of("No specific evidence returned") : cleaned;
	}

	private List<String> sourceContextIds(List<String> responseContextIds, List<String> fallbackContextIds) {
		List<String> availableIds = cleanContextIds(fallbackContextIds);
		List<String> responseIds = cleanContextIds(responseContextIds);
		if (availableIds.isEmpty()) {
			return responseIds;
		}
		List<String> retrievedIds = responseIds.stream()
			.filter(availableIds::contains)
			.toList();
		return retrievedIds.isEmpty() ? availableIds : retrievedIds;
	}

	private List<String> cleanContextIds(List<String> contextIds) {
		return safeList(contextIds).stream()
			.filter(Objects::nonNull)
			.map(String::trim)
			.filter(value -> !value.isBlank())
			.distinct()
			.limit(12)
			.toList();
	}

	private List<RecommendationResponse> nonEmptyRecommendations(List<RecommendationResponse> recommendations) {
		List<RecommendationResponse> cleaned = safeList(recommendations).stream()
			.filter(Objects::nonNull)
			.filter(recommendation -> !blank(recommendation.message()))
			.limit(6)
			.map(recommendation -> new RecommendationResponse(
				fallback(recommendation.section(), "Resume"),
				normalizePriority(recommendation.priority()),
				recommendation.message()
			))
			.toList();
		return cleaned.isEmpty()
			? List.of(new RecommendationResponse("Resume", "high", "Add more specific evidence, scope, and measurable outcomes."))
			: cleaned;
	}

	private String normalizePriority(String priority) {
		String normalized = fallback(priority, "medium").toLowerCase(Locale.ROOT);
		if (normalized.equals("high") || normalized.equals("medium") || normalized.equals("low")) {
			return normalized;
		}
		return "medium";
	}

	private String normalizeDifficulty(String difficulty) {
		String normalized = fallback(difficulty, "Core").toLowerCase(Locale.ROOT);
		if (normalized.contains("warm")) {
			return "Warmup";
		}
		if (normalized.contains("deep")) {
			return "Deep Dive";
		}
		return "Core";
	}

	private int average(AssessmentScores scores) {
		return Math.round((scores.technicalDepth() + scores.impact() + scores.clarity() + scores.relevance() + scores.ats()) / 5.0f);
	}

	private int clampScore(int score) {
		return Math.max(0, Math.min(100, score));
	}

	private String slug(String value) {
		String slug = fallback(value, "question")
			.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("(^-|-$)", "");
		if (slug.isBlank()) {
			return UUID.randomUUID().toString();
		}
		return slug.length() > 44 ? slug.substring(0, 44) : slug;
	}

	private String fallback(String value, String fallback) {
		return blank(value) ? fallback : value.trim();
	}

	private boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private <T> List<T> safeList(List<T> values) {
		return values == null ? List.of() : values;
	}
}
