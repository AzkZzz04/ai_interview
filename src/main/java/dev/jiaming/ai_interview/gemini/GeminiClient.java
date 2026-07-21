package dev.jiaming.ai_interview.gemini;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import dev.jiaming.ai_interview.coach.StructuredGenerationClient;

@Component
public class GeminiClient implements StructuredGenerationClient {

	private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

	private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";

	private final ObjectMapper objectMapper;

	private final GeminiTransport transport;

	private final MeterRegistry meterRegistry;

	private final String baseUrl;

	private final String apiKey;

	private final String model;

	private final double temperature;

	private final Duration requestTimeout;

	private final int maxOutputTokens;

	private final int thinkingBudget;

	@Autowired
	public GeminiClient(ObjectMapper objectMapper, Environment environment, MeterRegistry meterRegistry) {
		this(
			objectMapper,
			jdkTransport(),
			meterRegistry,
			DEFAULT_BASE_URL,
			environment.getProperty("spring.ai.google.genai.api-key", ""),
			environment.getProperty("spring.ai.google.genai.chat.options.model", "gemini-2.5-flash"),
			environment.getProperty("spring.ai.google.genai.chat.options.temperature", Double.class, 0.2),
			Duration.ofSeconds(environment.getProperty("app.gemini.request-timeout-seconds", Long.class, 90L)),
			environment.getProperty("app.gemini.max-output-tokens", Integer.class, 2_048),
			environment.getProperty("app.gemini.thinking-budget", Integer.class, 0)
		);
	}

	GeminiClient(
		ObjectMapper objectMapper,
		GeminiTransport transport,
		MeterRegistry meterRegistry,
		String baseUrl,
		String apiKey,
		String model,
		double temperature,
		Duration requestTimeout,
		int maxOutputTokens,
		int thinkingBudget
	) {
		this.objectMapper = objectMapper;
		this.transport = transport;
		this.meterRegistry = meterRegistry;
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
		this.apiKey = apiKey;
		this.model = model;
		this.temperature = temperature;
		this.requestTimeout = requestTimeout;
		this.maxOutputTokens = maxOutputTokens;
		this.thinkingBudget = thinkingBudget;
	}

	@Override
	public String generateJson(String prompt) {
		if (apiKey == null || apiKey.isBlank()) {
			throw failure(GeminiErrorCode.NOT_CONFIGURED, "Gemini is not configured", null, false);
		}

		long startedAt = System.nanoTime();
		log.info("gemini_request_start model={} timeoutSeconds={}", model, requestTimeout.toSeconds());
		try {
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(endpoint()))
				.timeout(requestTimeout)
				.header("Content-Type", "application/json")
				.header("x-goog-api-key", apiKey)
				.POST(HttpRequest.BodyPublishers.ofString(requestBody(prompt)))
				.build();

			GeminiTransportResponse response = transport.send(request);
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw httpFailure(response.statusCode());
			}

			String result = extractText(response.body());
			recordCall("success", startedAt);
			return result;
		}
		catch (HttpTimeoutException exception) {
			recordCall("timeout", startedAt);
			throw failure(GeminiErrorCode.TIMEOUT, "Gemini request timed out", exception, true);
		}
		catch (IOException exception) {
			recordCall("network", startedAt);
			throw failure(GeminiErrorCode.UPSTREAM_ERROR, "Gemini could not be reached", exception, true);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			recordCall("interrupted", startedAt);
			throw failure(GeminiErrorCode.TIMEOUT, "Gemini request was interrupted", exception, true);
		}
		catch (GeminiException exception) {
			recordCall(metricReason(exception.code()), startedAt);
			throw exception;
		}
	}

	private String requestBody(String prompt) throws JsonProcessingException {
		return objectMapper.writeValueAsString(Map.of(
			"contents", List.of(Map.of(
				"role", "user",
				"parts", List.of(Map.of("text", prompt))
			)),
			"generationConfig", generationConfig()
		));
	}

	private Map<String, Object> generationConfig() {
		Map<String, Object> generationConfig = new LinkedHashMap<>();
		generationConfig.put("temperature", temperature);
		generationConfig.put("responseMimeType", "application/json");
		generationConfig.put("maxOutputTokens", maxOutputTokens);
		if (model.startsWith("gemini-2.5")) {
			generationConfig.put("thinkingConfig", Map.of("thinkingBudget", thinkingBudget));
		}
		return generationConfig;
	}

	private String endpoint() {
		return baseUrl + URLEncoder.encode(model, StandardCharsets.UTF_8) + ":generateContent";
	}

	private String extractText(String responseBody) {
		final JsonNode root;
		try {
			root = objectMapper.readTree(responseBody);
		}
		catch (JsonProcessingException exception) {
			throw failure(GeminiErrorCode.UPSTREAM_ERROR, "Gemini returned an unreadable response", null, true);
		}

		JsonNode candidate = root.path("candidates").path(0);
		if (candidate.isMissingNode()) {
			String blockReason = root.path("promptFeedback").path("blockReason").asText("")
				.trim()
				.toUpperCase(Locale.ROOT);
			if (!blockReason.isEmpty()) {
				throw finishReasonFailure(blockReason);
			}
			throw failure(GeminiErrorCode.EMPTY_RESPONSE, "Gemini returned no candidate", null, true);
		}

		String finishReason = candidate.path("finishReason").asText("").trim().toUpperCase(Locale.ROOT);
		if (!"STOP".equals(finishReason)) {
			throw finishReasonFailure(finishReason);
		}

		JsonNode parts = candidate.path("content").path("parts");
		StringBuilder text = new StringBuilder();
		if (parts.isArray()) {
			for (JsonNode part : parts) {
				String value = part.path("text").asText("");
				if (!value.isBlank()) {
					if (!text.isEmpty()) {
						text.append('\n');
					}
					text.append(value);
				}
			}
		}
		if (text.isEmpty()) {
			throw failure(GeminiErrorCode.EMPTY_RESPONSE, "Gemini returned an empty candidate", null, true);
		}
		return stripJsonFence(text.toString());
	}

	private GeminiException finishReasonFailure(String finishReason) {
		return switch (finishReason) {
			case "SAFETY" -> failure(GeminiErrorCode.SAFETY, "Gemini blocked the response for safety", null, false);
			case "RECITATION" -> failure(GeminiErrorCode.RECITATION, "Gemini blocked the response for recitation", null, false);
			case "MAX_TOKENS" -> failure(GeminiErrorCode.MAX_TOKENS, "Gemini reached the output token limit", null, false);
			default -> failure(GeminiErrorCode.UPSTREAM_ERROR, "Gemini ended with an unsupported finish reason", null, false);
		};
	}

	private GeminiException httpFailure(int statusCode) {
		if (statusCode == 429) {
			return new GeminiException(GeminiErrorCode.RATE_LIMITED, "Gemini rate limit exceeded", statusCode, true);
		}
		boolean retryable = statusCode == 408 || statusCode >= 500;
		return new GeminiException(GeminiErrorCode.UPSTREAM_ERROR, "Gemini request failed", statusCode, retryable);
	}

	private GeminiException failure(String code, String message, Throwable cause, boolean retryable) {
		return new GeminiException(code, message, cause, retryable);
	}

	private void recordCall(String outcome, long startedAt) {
		long elapsedMs = elapsedMillis(startedAt);
		meterRegistry.counter("ai.gemini.calls", "outcome", outcome, "model", model).increment();
		meterRegistry.timer("ai.gemini.duration", "outcome", outcome, "model", model)
			.record(Duration.ofMillis(elapsedMs));
		log.info("gemini_request_complete model={} outcome={} elapsedMs={}", model, outcome, elapsedMs);
	}

	private String metricReason(String code) {
		return code == null ? "unknown" : code.toLowerCase(Locale.ROOT).replace("gemini_", "");
	}

	private String stripJsonFence(String value) {
		String trimmed = value.trim();
		if (trimmed.startsWith("```")) {
			trimmed = trimmed.replaceFirst("^```(?:json)?\\s*", "");
			trimmed = trimmed.replaceFirst("\\s*```$", "");
		}
		return trimmed.trim();
	}

	private long elapsedMillis(long startedAt) {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
	}

	private static GeminiTransport jdkTransport() {
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();
		return request -> {
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			return new GeminiTransportResponse(response.statusCode(), response.body());
		};
	}
}
