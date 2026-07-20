package dev.jiaming.ai_interview.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.redis")
public record RedisUsageProperties(
	RateLimit rateLimit,
	InFlight inFlight,
	Idempotency idempotency,
	AiResultCache aiResultCache
) {

	public RedisUsageProperties {
		rateLimit = rateLimit == null ? new RateLimit(true, 60, 12, 20) : rateLimit;
		inFlight = inFlight == null ? new InFlight(true, 120) : inFlight;
		idempotency = idempotency == null ? new Idempotency(true, 86_400) : idempotency;
		aiResultCache = aiResultCache == null ? new AiResultCache(true, 300) : aiResultCache;
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

	public record InFlight(
		boolean enabled,
		int lockTtlSeconds
	) {

		public InFlight {
			lockTtlSeconds = lockTtlSeconds <= 0 ? 120 : lockTtlSeconds;
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

	public record AiResultCache(
		boolean enabled,
		int ttlSeconds
	) {

		public AiResultCache {
			ttlSeconds = ttlSeconds <= 0 ? 300 : ttlSeconds;
		}
	}
}
