package dev.jiaming.ai_interview.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RedisRequestGuard {

	private static final Logger log = LoggerFactory.getLogger(RedisRequestGuard.class);

	private final StringRedisTemplate redisTemplate;

	private final RedisUsageProperties properties;

	private final ObjectMapper objectMapper;

	public RedisRequestGuard(
		StringRedisTemplate redisTemplate,
		RedisUsageProperties properties,
		ObjectMapper objectMapper
	) {
		this.redisTemplate = redisTemplate;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	public void assertAiAllowed(String action) {
		assertAllowed(action, properties.rateLimit().aiLimit());
	}

	public void assertUploadAllowed() {
		assertAllowed("resume-upload", properties.rateLimit().uploadLimit());
	}

	public <T> T withIdempotentRetryCache(
		String action,
		Object requestFingerprintSource,
		Class<T> responseType,
		Supplier<T> work
	) {
		if (!properties.idempotency().enabled()) {
			return work.get();
		}

		Optional<String> idempotencyKey = idempotencyKey();
		if (idempotencyKey.isEmpty()) {
			return work.get();
		}

		String requestFingerprint = fingerprint(action, requestFingerprintSource);
		String baseKey = key("idem:%s:%s:%s".formatted(action, clientId(), sha256(idempotencyKey.get())));
		String fingerprintKey = baseKey + ":fingerprint";
		String responseKey = baseKey + ":response";
		Duration ttl = Duration.ofSeconds(properties.idempotency().ttlSeconds());

		try {
			T cachedResponse = cachedResponse(action, fingerprintKey, responseKey, requestFingerprint, responseType, ttl);
			if (cachedResponse != null) {
				return cachedResponse;
			}
		}
		catch (ResponseStatusException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			log.warn("redis_idempotency_cache_unavailable action={} reason={}", action, exception.getMessage());
			return work.get();
		}

		T response = work.get();
		storeResponse(action, fingerprintKey, responseKey, requestFingerprint, response, ttl);
		return response;
	}

	private void assertAllowed(String action, int limit) {
		if (!properties.rateLimit().enabled()) {
			return;
		}

		long bucket = Instant.now().getEpochSecond() / properties.rateLimit().windowSeconds();
		String key = key("rate:%s:%s:%d".formatted(action, clientId(), bucket));
		try {
			Long count = redisTemplate.opsForValue().increment(key);
			if (count != null && count == 1) {
				redisTemplate.expire(key, Duration.ofSeconds(properties.rateLimit().windowSeconds() * 2L));
			}
			if (count != null && count > limit) {
				throw new ResponseStatusException(
					HttpStatus.TOO_MANY_REQUESTS,
					"Too many " + action + " requests. Try again in about "
						+ properties.rateLimit().windowSeconds() + " seconds."
				);
			}
		}
		catch (ResponseStatusException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			log.warn("redis_rate_limit_unavailable action={} reason={}", action, exception.getMessage());
		}
	}

	private <T> T cachedResponse(
		String action,
		String fingerprintKey,
		String responseKey,
		String requestFingerprint,
		Class<T> responseType,
		Duration ttl
	) {
		String storedFingerprint = redisTemplate.opsForValue().get(fingerprintKey);
		if (storedFingerprint != null && !storedFingerprint.equals(requestFingerprint)) {
			throw new ResponseStatusException(
				HttpStatus.CONFLICT,
				"Idempotency-Key was already used for a different " + action + " request."
			);
		}

		if (storedFingerprint != null) {
			String responseJson = redisTemplate.opsForValue().get(responseKey);
			if (responseJson == null) {
				return null;
			}
			try {
				return objectMapper.readValue(responseJson, responseType);
			}
			catch (JsonProcessingException exception) {
				log.warn("redis_idempotency_cache_decode_failed action={} reason={}", action, exception.getMessage());
				return null;
			}
		}

		Boolean stored = redisTemplate.opsForValue().setIfAbsent(fingerprintKey, requestFingerprint, ttl);
		if (Boolean.FALSE.equals(stored)) {
			storedFingerprint = redisTemplate.opsForValue().get(fingerprintKey);
			if (storedFingerprint != null && !storedFingerprint.equals(requestFingerprint)) {
				throw new ResponseStatusException(
					HttpStatus.CONFLICT,
					"Idempotency-Key was already used for a different " + action + " request."
				);
			}
		}
		return null;
	}

	private void storeResponse(
		String action,
		String fingerprintKey,
		String responseKey,
		String requestFingerprint,
		Object response,
		Duration ttl
	) {
		try {
			redisTemplate.opsForValue().set(fingerprintKey, requestFingerprint, ttl);
			redisTemplate.opsForValue().set(responseKey, objectMapper.writeValueAsString(response), ttl);
		}
		catch (JsonProcessingException exception) {
			log.warn("redis_idempotency_cache_encode_failed action={} reason={}", action, exception.getMessage());
		}
		catch (RuntimeException exception) {
			log.warn("redis_idempotency_cache_store_failed action={} reason={}", action, exception.getMessage());
		}
	}

	private String clientId() {
		if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
			HttpServletRequest request = attributes.getRequest();
			String remoteAddress = request.getRemoteAddr();
			if (remoteAddress != null && !remoteAddress.isBlank()) {
				return sanitize(remoteAddress);
			}
		}
		return "local";
	}

	private Optional<String> idempotencyKey() {
		if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
			String value = attributes.getRequest().getHeader("Idempotency-Key");
			if (value != null && !value.isBlank()) {
				return Optional.of(value.trim());
			}
		}
		return Optional.empty();
	}

	private String sanitize(String value) {
		return value.replaceAll("[^A-Za-z0-9._:-]", "_");
	}

	private String fingerprint(String action, Object requestFingerprintSource) {
		try {
			return sha256(objectMapper.writeValueAsString(List.of(action, requestFingerprintSource)));
		}
		catch (JsonProcessingException exception) {
			return sha256(action + ":" + String.valueOf(requestFingerprintSource));
		}
	}

	private String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private String key(String suffix) {
		return properties.keyPrefix() + suffix;
	}
}
