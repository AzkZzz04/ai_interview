package dev.jiaming.ai_interview.common;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "app.redis")
public record RedisUsageProperties(
	String keyPrefix,
	RateLimit rateLimit,
	Idempotency idempotency
) {

	@ConstructorBinding
	public RedisUsageProperties {
		keyPrefix = normalizedPrefix(keyPrefix);
		rateLimit = rateLimit == null ? new RateLimit(true, 60, 12, 20) : rateLimit;
		idempotency = idempotency == null ? new Idempotency(true, 86_400) : idempotency;
	}

	public RedisUsageProperties(RateLimit rateLimit, Idempotency idempotency) {
		this("ai-interview:v3:", rateLimit, idempotency);
	}

	private static String normalizedPrefix(String value) {
		String prefix = value == null || value.isBlank() ? "ai-interview:v3:" : value.trim();
		return prefix.endsWith(":") ? prefix : prefix + ":";
	}

	public record RateLimit(
		boolean enabled,
		int windowSeconds,
		int aiLimit,
		int uploadLimit
	) {

		public RateLimit {
			windowSeconds = windowSeconds <= 0 ? 60 : windowSeconds;
			aiLimit = aiLimit <= 0 ? 12 : aiLimit;
			uploadLimit = uploadLimit <= 0 ? 20 : uploadLimit;
		}
	}

	public record Idempotency(
		boolean enabled,
		int ttlSeconds
	) {

		public Idempotency {
			ttlSeconds = ttlSeconds <= 0 ? 86_400 : ttlSeconds;
		}
	}

}
