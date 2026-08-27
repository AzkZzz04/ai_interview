package dev.jiaming.ai_interview.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class GeminiClientTests {

	private static final String API_KEY = "secret-key-marker";

	@Test
	void sendsApiKeyInHeaderAndNotInUrl() {
		AtomicReference<HttpRequest> captured = new AtomicReference<>();
		GeminiClient client = client(request -> {
			captured.set(request);
			return new GeminiTransportResponse(200, response("STOP", "{\"ok\":true}"));
		});

		assertThat(client.generateJson("prompt")).isEqualTo("{\"ok\":true}");
		assertThat(captured.get().headers().firstValue("x-goog-api-key")).contains(API_KEY);
		assertThat(captured.get().uri().toString()).doesNotContain(API_KEY).doesNotContain("?key=");
	}

	@Test
	void usesGeminiThreeThinkingLevelWithoutDeprecatedTemperature() {
		AtomicReference<HttpRequest> captured = new AtomicReference<>();
		GeminiClient client = new GeminiClient(
			new ObjectMapper(),
			request -> {
				captured.set(request);
				return new GeminiTransportResponse(200, response("STOP", "{\"ok\":true}"));
			},
			new SimpleMeterRegistry(),
			"https://example.test/v1beta/models/",
			API_KEY,
			"gemini-3.6-flash",
			0.2,
			Duration.ofSeconds(5),
			2_048,
			0,
			"medium"
		);

		client.generateJson("prompt");

		assertThat(captured.get().uri().toString()).contains("gemini-3.6-flash");
		assertThat(requestBody(captured.get()))
			.contains("\"thinkingLevel\":\"medium\"")
			.doesNotContain("temperature")
			.doesNotContain("thinkingBudget");
	}

	@Test
	void acceptsOnlyStopAndClassifiesFinishReasonsWithoutRetry() {
		assertFinishReason("SAFETY", GeminiErrorCode.SAFETY);
		assertFinishReason("RECITATION", GeminiErrorCode.RECITATION);
		assertFinishReason("MAX_TOKENS", GeminiErrorCode.MAX_TOKENS);
		assertFinishReason("OTHER", GeminiErrorCode.UPSTREAM_ERROR);
	}

	@Test
	void emptyCandidateIsRetryableAndDoesNotExposeRawResponse() {
		String rawMarker = "private-resume-output";
		GeminiClient client = client(request -> new GeminiTransportResponse(
			200,
			"{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[]}}],"
				+ "\"debug\":\"" + rawMarker + "\"}"
		));

		assertThatThrownBy(() -> client.generateJson("prompt"))
			.isInstanceOfSatisfying(GeminiException.class, exception -> {
				assertThat(exception.code()).isEqualTo(GeminiErrorCode.EMPTY_RESPONSE);
				assertThat(exception.retryable()).isTrue();
				assertThat(exception.getMessage()).doesNotContain(rawMarker).doesNotContain(API_KEY);
			});
	}

	@Test
	void safetyPromptBlockWithoutCandidateIsNotRetryable() {
		GeminiClient client = client(request -> new GeminiTransportResponse(
			200,
			"{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}"
		));

		assertThatThrownBy(() -> client.generateJson("prompt"))
			.isInstanceOfSatisfying(GeminiException.class, exception -> {
				assertThat(exception.code()).isEqualTo(GeminiErrorCode.SAFETY);
				assertThat(exception.retryable()).isFalse();
			});
	}

	@Test
	void maxTokensUsesOneTransportCallAndIsNotRetryable() {
		AtomicInteger calls = new AtomicInteger();
		GeminiClient client = client(request -> {
			calls.incrementAndGet();
			return new GeminiTransportResponse(200, response("MAX_TOKENS", "partial"));
		});

		assertThatThrownBy(() -> client.generateJson("prompt"))
			.isInstanceOfSatisfying(GeminiException.class, exception -> {
				assertThat(exception.code()).isEqualTo(GeminiErrorCode.MAX_TOKENS);
				assertThat(exception.retryable()).isFalse();
			});
		assertThat(calls).hasValue(1);
	}

	@Test
	void rateLimitUsesFixedRetryableCodeWithoutResponseBody() {
		String rawMarker = "upstream-body-marker";
		GeminiClient client = client(request -> new GeminiTransportResponse(429, rawMarker));

		assertThatThrownBy(() -> client.generateJson("prompt"))
			.isInstanceOfSatisfying(GeminiException.class, exception -> {
				assertThat(exception.code()).isEqualTo(GeminiErrorCode.RATE_LIMITED);
				assertThat(exception.retryable()).isTrue();
				assertThat(exception.getMessage()).doesNotContain(rawMarker).doesNotContain(API_KEY);
			});
	}

	@Test
	void blankApiKeyIsNotConfiguredAndSkipsTheTransport() {
		AtomicInteger calls = new AtomicInteger();
		GeminiClient client = clientWithKey("", request -> {
			calls.incrementAndGet();
			return new GeminiTransportResponse(200, response("STOP", "{}"));
		});

		assertThatThrownBy(() -> client.generateJson("prompt"))
			.isInstanceOfSatisfying(GeminiException.class, exception -> {
				assertThat(exception.code()).isEqualTo(GeminiErrorCode.NOT_CONFIGURED);
				assertThat(exception.retryable()).isFalse();
			});
		assertThat(calls).hasValue(0);
	}

	@Test
	void serverAndTimeoutStatusesAreRetryableUpstreamErrors() {
		for (int status : new int[] { 408, 500, 503 }) {
			GeminiClient client = client(request -> new GeminiTransportResponse(status, "body"));
			assertThatThrownBy(() -> client.generateJson("prompt"))
				.isInstanceOfSatisfying(GeminiException.class, exception -> {
					assertThat(exception.code()).isEqualTo(GeminiErrorCode.UPSTREAM_ERROR);
					assertThat(exception.retryable()).isTrue();
				});
		}
	}

	@Test
	void clientErrorsOtherThan429AreNotRetryable() {
		for (int status : new int[] { 400, 403, 404 }) {
			GeminiClient client = client(request -> new GeminiTransportResponse(status, "body"));
			assertThatThrownBy(() -> client.generateJson("prompt"))
				.isInstanceOfSatisfying(GeminiException.class, exception -> {
					assertThat(exception.code()).isEqualTo(GeminiErrorCode.UPSTREAM_ERROR);
					assertThat(exception.retryable()).isFalse();
				});
		}
	}

	@Test
	void transportTimeoutIsRetryable() {
		GeminiClient client = client(request -> {
			throw new HttpTimeoutException("timed out");
		});

		assertThatThrownBy(() -> client.generateJson("prompt"))
			.isInstanceOfSatisfying(GeminiException.class, exception -> {
				assertThat(exception.code()).isEqualTo(GeminiErrorCode.TIMEOUT);
				assertThat(exception.retryable()).isTrue();
			});
	}

	@Test
	void networkErrorIsRetryable() {
		GeminiClient client = client(request -> {
			throw new IOException("connection reset");
		});

		assertThatThrownBy(() -> client.generateJson("prompt"))
			.isInstanceOfSatisfying(GeminiException.class, exception -> {
				assertThat(exception.code()).isEqualTo(GeminiErrorCode.UPSTREAM_ERROR);
				assertThat(exception.retryable()).isTrue();
			});
	}

	@Test
	void unreadableResponseBodyIsRetryable() {
		GeminiClient client = client(request -> new GeminiTransportResponse(200, "<<not json>>"));

		assertThatThrownBy(() -> client.generateJson("prompt"))
			.isInstanceOfSatisfying(GeminiException.class, exception -> {
				assertThat(exception.code()).isEqualTo(GeminiErrorCode.UPSTREAM_ERROR);
				assertThat(exception.retryable()).isTrue();
			});
	}

	@Test
	void stripsJsonCodeFenceFromModelOutput() {
		GeminiClient client = client(request -> new GeminiTransportResponse(
			200, stopResponse("```json\n{\"ok\":true}\n```")
		));

		assertThat(client.generateJson("prompt")).isEqualTo("{\"ok\":true}");
	}

	@Test
	void concatenatesMultipleTextParts() {
		GeminiClient client = client(request -> new GeminiTransportResponse(
			200, stopResponse("{\"a\":1,", "\"b\":2}")
		));

		assertThat(client.generateJson("prompt")).isEqualTo("{\"a\":1,\n\"b\":2}");
	}

	private void assertFinishReason(String finishReason, String expectedCode) {
		GeminiClient client = client(request -> new GeminiTransportResponse(200, response(finishReason, "raw-marker")));
		assertThatThrownBy(() -> client.generateJson("prompt"))
			.isInstanceOfSatisfying(GeminiException.class, exception -> {
				assertThat(exception.code()).isEqualTo(expectedCode);
				assertThat(exception.retryable()).isFalse();
				assertThat(exception.getMessage()).doesNotContain("raw-marker").doesNotContain(API_KEY);
			});
	}

	private GeminiClient client(GeminiTransport transport) {
		return clientWithKey(API_KEY, transport);
	}

	private String requestBody(HttpRequest request) {
		StringBuilder body = new StringBuilder();
		CompletableFuture<Void> completed = new CompletableFuture<>();
		request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
			@Override
			public void onSubscribe(Flow.Subscription subscription) {
				subscription.request(Long.MAX_VALUE);
			}

			@Override
			public void onNext(ByteBuffer item) {
				body.append(StandardCharsets.UTF_8.decode(item));
			}

			@Override
			public void onError(Throwable throwable) {
				completed.completeExceptionally(throwable);
			}

			@Override
			public void onComplete() {
				completed.complete(null);
			}
		});
		completed.join();
		return body.toString();
	}

	private GeminiClient clientWithKey(String apiKey, GeminiTransport transport) {
		return new GeminiClient(
			new ObjectMapper(),
			transport,
			new SimpleMeterRegistry(),
			"https://example.test/v1beta/models/",
			apiKey,
			"gemini-2.5-flash",
			0.2,
			Duration.ofSeconds(5),
			2_048,
			0
		);
	}

	private String response(String finishReason, String text) {
		return "{\"candidates\":[{\"finishReason\":\"" + finishReason
			+ "\",\"content\":{\"parts\":[{\"text\":\"" + text.replace("\"", "\\\"") + "\"}]}}]}";
	}

	private String stopResponse(String... texts) {
		List<Map<String, Object>> parts = Arrays.stream(texts)
			.map(text -> Map.<String, Object>of("text", text))
			.toList();
		try {
			return new ObjectMapper().writeValueAsString(Map.of(
				"candidates", List.of(Map.of(
					"finishReason", "STOP",
					"content", Map.of("parts", parts)
				))
			));
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
