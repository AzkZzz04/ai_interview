package dev.jiaming.ai_interview.jobs;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import dev.jiaming.ai_interview.common.RedisUsageProperties;

@Service
public class JobResultCache {

	private static final Logger log = LoggerFactory.getLogger(JobResultCache.class);

	private final StringRedisTemplate redisTemplate;

	private final JobProperties properties;

	private final RedisUsageProperties redisProperties;

	public JobResultCache(
		StringRedisTemplate redisTemplate,
		JobProperties properties,
		RedisUsageProperties redisProperties
	) {
		this.redisTemplate = redisTemplate;
		this.properties = properties;
		this.redisProperties = redisProperties;
	}

	public Optional<UUID> find(UUID userId, JobType type, String fingerprint) {
		if (!redisProperties.aiResultCache().enabled()) {
			return Optional.empty();
		}
		try {
			String value = redisTemplate.opsForValue().get(key(userId, type, fingerprint));
			return value == null ? Optional.empty() : Optional.of(UUID.fromString(value));
		}
		catch (RuntimeException exception) {
			log.warn("job_result_cache_unavailable operation=read reason={}", exception.getMessage());
			return Optional.empty();
		}
	}

	public void put(UUID userId, JobType type, String fingerprint, UUID jobId) {
		if (!redisProperties.aiResultCache().enabled()) {
			return;
		}
		try {
			redisTemplate.opsForValue().set(
				key(userId, type, fingerprint),
				jobId.toString(),
				Duration.ofSeconds(Math.min(
					properties.reuseWindowSeconds(),
					redisProperties.aiResultCache().ttlSeconds()
				))
			);
		}
		catch (RuntimeException exception) {
			log.warn("job_result_cache_unavailable operation=write reason={}", exception.getMessage());
		}
	}

	private String key(UUID userId, JobType type, String fingerprint) {
		return "job-result:%s:%s:%s".formatted(type.name().toLowerCase(), userId, fingerprint);
	}
}
