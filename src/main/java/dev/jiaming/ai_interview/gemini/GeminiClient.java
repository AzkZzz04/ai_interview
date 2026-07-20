package dev.jiaming.ai_interview.gemini;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class GeminiClient {

	private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

	private final HttpClient httpClient;

	private final ObjectMapper objectMapper;

	private final String apiKey;

	private final String model;

	private final double temperature;

	private final Duration requestTimeout;

	private final int maxOutputTokens;

	private final int thinkingBudget;

	public GeminiClient(ObjectMapper objectMapper, Environment environment) {
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();
		this.objectMapper = objectMapper;
		this.apiKey = environment.getProperty("spring.ai.google.genai.api-key", "");
		this.model = environment.getProperty("spring.ai.google.genai.chat.options.model", "gemini-2.0-flash");
		this.temperature = environment.getProperty("spring.ai.google.genai.chat.options.temperature", Double.class, 0.2);
		this.requestTimeout = Duration.ofSeconds(environment.getProperty("app.gemini.request-timeout-seconds", Long.class, 90L));
		this.maxOutputTokens = environment.getProperty("app.gemini.max-output-tokens", Integer.class, 2_048);
		this.thinkingBudget = environment.getProperty("app.gemini.thinking-budget", Integer.class, 0);
	}

	public String generateJson(String prompt) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GEMINI_API_KEY is not configured");
		}

		long startedAt = System.nanoTime();
		log.info("gemini_request_start model={} timeoutSeconds={}", model, requestTimeout.toSeconds());
		try {
			String requestBody = objectMapper.writeValueAsString(Map.of(
				"contents", List.of(Map.of(
					"role", "user",
					"parts", List.of(Map.of("text", prompt))
				)),
				"generationConfig", generationConfig()
			));

			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(endpoint()))
				.timeout(requestTimeout)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(requestBody))
				.build();

			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			log.info(
				"gemini_request_complete model={} status={} elapsedMs={}",
				model,
				response.statusCode(),
				elapsedMillis(startedAt)
			);
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				int statusCode = response.statusCode();
				boolean retryable = statusCode == 408 || statusCode == 429 || statusCode >= 500;
				throw new GeminiException(
					"Gemini request failed: " + geminiErrorMessage(response.body()),
					statusCode,
					retryable
				);
			}

			return extractText(response.body());
		}
		catch (IOException exception) {
			log.warn("gemini_request_failed model={} elapsedMs={}", model, elapsedMillis(startedAt));
			throw new GeminiException("Could not call Gemini", exception);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			log.warn("gemini_request_interrupted model={} elapsedMs={}", model, elapsedMillis(startedAt));
			throw new GeminiException("Gemini request was interrupted", exception);
		}
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
		String encodedModel = URLEncoder.encode(model, StandardCharsets.UTF_8);
		String encodedKey = URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
		return "https://generativelanguage.googleapis.com/v1beta/models/" + encodedModel
			+ ":generateContent?key=" + encodedKey;
	}

	private String extractText(String responseBody) {
		try {
			JsonNode root = objectMapper.readTree(responseBody);
			JsonNode textNode = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
			if (textNode.isMissingNode() || textNode.asText().isBlank()) {
				throw new GeminiException("Gemini returned an empty response");
			}
			return stripJsonFence(textNode.asText());
		}
		catch (IOException exception) {
			throw new GeminiException("Could not parse Gemini response", exception);
		}
	}

	private String geminiErrorMessage(String responseBody) {
		try {
			JsonNode root = objectMapper.readTree(responseBody);
			String message = root.path("error").path("message").asText();
			return message.isBlank() ? "status response body was empty" : message;
		}
		catch (IOException exception) {
			return "status response body could not be parsed";
		}
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
}
