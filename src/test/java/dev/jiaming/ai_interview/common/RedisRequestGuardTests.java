package dev.jiaming.ai_interview.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisRequestGuardTests {

	private final Map<String, String> redis = new ConcurrentHashMap<>();

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private ValueOperations<String, String> valueOperations;

	private RedisRequestGuard guard;

	@BeforeEach
	void setUp() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get(anyString())).thenAnswer(invocation -> redis.get(invocation.getArgument(0)));
		when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
			.thenAnswer(invocation -> redis.putIfAbsent(invocation.getArgument(0), invocation.getArgument(1)) == null);
		doAnswer(invocation -> {
			redis.put(invocation.getArgument(0), invocation.getArgument(1));
			return null;
		}).when(valueOperations).set(anyString(), anyString(), any(Duration.class));
		when(valueOperations.increment(anyString())).thenAnswer(invocation -> {
			String key = invocation.getArgument(0);
			long nextValue = Long.parseLong(redis.getOrDefault(key, "0")) + 1;
			redis.put(key, Long.toString(nextValue));
			return nextValue;
		});
		when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);

		guard = new RedisRequestGuard(
			redisTemplate,
			new RedisUsageProperties(
				new RedisUsageProperties.RateLimit(true, 60, 2, 2),
				new RedisUsageProperties.Idempotency(true, 86_400)
			),
			objectMapper()
		);
	}

	@AfterEach
	void tearDown() {
		RequestContextHolder.resetRequestAttributes();
	}

	@Test
	void replaysCachedResponseForSameIdempotencyKeyAndPayload() {
		requestWithIdempotencyKey("retry-key");
		AtomicInteger calls = new AtomicInteger();

		CachedResponse first = guard.withIdempotentRetryCache(
			"assessment",
			List.of("resume", "job"),
			CachedResponse.class,
			() -> new CachedResponse("run-" + calls.incrementAndGet())
		);
		CachedResponse second = guard.withIdempotentRetryCache(
			"assessment",
			List.of("resume", "job"),
			CachedResponse.class,
			() -> new CachedResponse("run-" + calls.incrementAndGet())
		);

		assertThat(first).isEqualTo(new CachedResponse("run-1"));
		assertThat(second).isEqualTo(first);
		assertThat(calls).hasValue(1);
	}

	@Test
	void rejectsSameIdempotencyKeyForDifferentPayload() {
		requestWithIdempotencyKey("retry-key");

		guard.withIdempotentRetryCache(
			"assessment",
			List.of("resume-a"),
			CachedResponse.class,
			() -> new CachedResponse("saved")
		);

		assertThatThrownBy(() -> guard.withIdempotentRetryCache(
			"assessment",
			List.of("resume-b"),
			CachedResponse.class,
			() -> new CachedResponse("should-not-run")
		))
			.isInstanceOf(ResponseStatusException.class)
			.hasMessageContaining("409 CONFLICT")
			.hasMessageContaining("Idempotency-Key");
	}

	@Test
	void bypassesIdempotencyCacheWhenHeaderIsMissing() {
		requestWithIdempotencyKey(null);
		AtomicInteger calls = new AtomicInteger();

		CachedResponse first = guard.withIdempotentRetryCache(
			"assessment",
			List.of("resume"),
			CachedResponse.class,
			() -> new CachedResponse("run-" + calls.incrementAndGet())
		);
		CachedResponse second = guard.withIdempotentRetryCache(
			"assessment",
			List.of("resume"),
			CachedResponse.class,
			() -> new CachedResponse("run-" + calls.incrementAndGet())
		);

		assertThat(first).isEqualTo(new CachedResponse("run-1"));
		assertThat(second).isEqualTo(new CachedResponse("run-2"));
		assertThat(calls).hasValue(2);
	}

	@Test
	void rateLimitsAiRequestsPerClientBucket() {
		requestWithIdempotencyKey(null);

		guard.assertAiAllowed("assessment");
		guard.assertAiAllowed("assessment");

		assertThatThrownBy(() -> guard.assertAiAllowed("assessment"))
			.isInstanceOf(ResponseStatusException.class)
			.hasMessageContaining("429 TOO_MANY_REQUESTS")
			.hasMessageContaining("Too many assessment requests");
	}

	@Test
	void rateLimitsUploadRequestsSeparatelyFromAiRequests() {
		requestWithIdempotencyKey(null);

		guard.assertUploadAllowed();
		guard.assertUploadAllowed();

		assertThatThrownBy(guard::assertUploadAllowed)
			.isInstanceOf(ResponseStatusException.class)
			.hasMessageContaining("429 TOO_MANY_REQUESTS")
			.hasMessageContaining("resume-upload");
	}

	@Test
	void namespacesRateLimitAndIdempotencyKeys() {
		requestWithIdempotencyKey("retry-key");
		guard.assertAiAllowed("assessment");
		guard.withIdempotentRetryCache(
			"assessment", List.of("resume"), CachedResponse.class, () -> new CachedResponse("saved")
		);

		assertThat(redis.keySet()).allMatch(key -> key.startsWith("ai-interview:v3:"));
	}

	@Test
	void doesNotTrustClientSuppliedForwardedAddressForRateLimits() {
		MockHttpServletRequest first = new MockHttpServletRequest();
		first.setRemoteAddr("203.0.113.10");
		first.addHeader("X-Forwarded-For", "198.51.100.1");
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(first));
		guard.assertAiAllowed("assessment");

		MockHttpServletRequest second = new MockHttpServletRequest();
		second.setRemoteAddr("203.0.113.10");
		second.addHeader("X-Forwarded-For", "198.51.100.2");
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(second));
		guard.assertAiAllowed("assessment");

		assertThatThrownBy(() -> guard.assertAiAllowed("assessment"))
			.isInstanceOf(ResponseStatusException.class)
			.hasMessageContaining("429 TOO_MANY_REQUESTS");
	}

	@Test
	void doesNotTouchRedisWhenIdempotencyIsDisabled() {
		requestWithIdempotencyKey("retry-key");
		RedisRequestGuard disabledGuard = new RedisRequestGuard(
			redisTemplate,
			new RedisUsageProperties(
				new RedisUsageProperties.RateLimit(true, 60, 2, 2),
				new RedisUsageProperties.Idempotency(false, 86_400)
			),
			objectMapper()
		);

		CachedResponse response = disabledGuard.withIdempotentRetryCache(
			"assessment",
			List.of("resume"),
			CachedResponse.class,
			() -> new CachedResponse("uncached")
		);

		assertThat(response).isEqualTo(new CachedResponse("uncached"));
		verifyNoInteractions(valueOperations);
	}

	private void requestWithIdempotencyKey(String idempotencyKey) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("203.0.113.10");
		if (idempotencyKey != null) {
			request.addHeader("Idempotency-Key", idempotencyKey);
		}
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
	}

	private ObjectMapper objectMapper() {
		return JsonMapper.builder()
			.findAndAddModules()
			.build();
	}

	private record CachedResponse(String value) {
	}
}
