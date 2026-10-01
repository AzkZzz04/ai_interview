package dev.jiaming.ai_interview.common

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.Optional
import java.util.function.Supplier
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException

@Service
class RedisRequestGuard(
    private val redisTemplate: StringRedisTemplate,
    private val properties: RedisUsageProperties,
    private val objectMapper: ObjectMapper
) {
    fun assertAiAllowed(action: String) = assertAllowed(action, properties.rateLimit.aiLimit)
    fun assertUploadAllowed() = assertAllowed("resume-upload", properties.rateLimit.uploadLimit)

    fun <T> withIdempotentRetryCache(
        action: String,
        requestFingerprintSource: Any?,
        responseType: Class<T>,
        work: Supplier<T>
    ): T {
        if (!properties.idempotency.enabled) return work.get()
        val idempotencyKey = idempotencyKey().orElse(null) ?: return work.get()
        val requestFingerprint = fingerprint(action, requestFingerprintSource)
        val baseKey = key("idem:%s:%s:%s".format(action, clientId(), sha256(idempotencyKey)))
        val fingerprintKey = "$baseKey:fingerprint"
        val responseKey = "$baseKey:response"
        val ttl = Duration.ofSeconds(properties.idempotency.ttlSeconds.toLong())

        try {
            val cached = cachedResponse(action, fingerprintKey, responseKey, requestFingerprint, responseType)
            if (cached != null) return cached
        } catch (exception: ResponseStatusException) {
            throw exception
        } catch (exception: RuntimeException) {
            log.warn("redis_idempotency_cache_unavailable action={} reason={}", action, exception.message)
            return work.get()
        }

        val response = try { work.get() } catch (failure: Throwable) {
            release(action, fingerprintKey)
            throw failure
        }
        storeResponse(action, fingerprintKey, responseKey, requestFingerprint, response, ttl)
        return response
    }

    /** Like [withIdempotentRetryCache], but a replay also returns the first response's HTTP status. */
    fun <T : Any> withIdempotentHttpCache(
        action: String,
        requestFingerprintSource: Any?,
        bodyType: Class<T>,
        work: Supplier<ResponseEntity<T>>
    ): ResponseEntity<T> {
        var fresh: ResponseEntity<T>? = null
        val cached = withIdempotentRetryCache(action, requestFingerprintSource, CachedHttpResponse::class.java) {
            val response = work.get().also { fresh = it }
            CachedHttpResponse(response.statusCode.value(), objectMapper.valueToTree(response.body))
        }
        return fresh ?: ResponseEntity.status(cached.status).body(cached.body?.let { objectMapper.treeToValue(it, bodyType) })
    }

    private fun assertAllowed(action: String, limit: Int) {
        if (!properties.rateLimit.enabled) return
        val bucket = Instant.now().epochSecond / properties.rateLimit.windowSeconds
        val redisKey = key("rate:%s:%s:%d".format(action, clientId(), bucket))
        try {
            val count = redisTemplate.opsForValue().increment(redisKey)
            if (count != null && count == 1L) {
                redisTemplate.expire(redisKey, Duration.ofSeconds(properties.rateLimit.windowSeconds * 2L))
            }
            if (count != null && count > limit) {
                throw ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many $action requests. Try again in about ${properties.rateLimit.windowSeconds} seconds."
                )
            }
        } catch (exception: ResponseStatusException) {
            throw exception
        } catch (exception: RuntimeException) {
            log.warn("redis_rate_limit_unavailable action={} reason={}", action, exception.message)
        }
    }

    // The first request reserves the key; a same-key replay returns its stored response, or a retryable 503 while it still runs.
    private fun <T> cachedResponse(
        action: String,
        fingerprintKey: String,
        responseKey: String,
        requestFingerprint: String,
        responseType: Class<T>
    ): T? {
        if (redisTemplate.opsForValue().setIfAbsent(fingerprintKey, requestFingerprint, IN_FLIGHT_TTL) == true) return null
        // ponytail: a reservation released between these two reads lets this request run unreserved; same as before the reservation existed.
        val storedFingerprint = redisTemplate.opsForValue().get(fingerprintKey) ?: return null
        if (storedFingerprint != requestFingerprint) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key was already used for a different $action request.")
        }
        val responseJson = redisTemplate.opsForValue().get(responseKey) ?: throw ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE, "The first $action request with this Idempotency-Key is still running. Try again shortly."
        )
        try {
            return objectMapper.readValue(responseJson, responseType)
        } catch (exception: JsonProcessingException) {
            log.warn("redis_idempotency_cache_decode_failed action={} reason={}", action, exception.message)
            return null
        }
    }

    // A failed request leaves nothing to replay, so its retry may run the work again.
    private fun release(action: String, fingerprintKey: String) {
        try {
            redisTemplate.delete(fingerprintKey)
        } catch (exception: RuntimeException) {
            log.warn("redis_idempotency_release_failed action={} reason={}", action, exception.message)
        }
    }

    private fun storeResponse(action: String, fingerprintKey: String, responseKey: String, fingerprint: String, response: Any?, ttl: Duration) {
        try {
            redisTemplate.opsForValue().set(fingerprintKey, fingerprint, ttl)
            redisTemplate.opsForValue().set(responseKey, objectMapper.writeValueAsString(response), ttl)
        } catch (exception: JsonProcessingException) {
            log.warn("redis_idempotency_cache_encode_failed action={} reason={}", action, exception.message)
        } catch (exception: RuntimeException) {
            log.warn("redis_idempotency_cache_store_failed action={} reason={}", action, exception.message)
        }
    }

    private fun clientId(): String {
        val attributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
        val remoteAddress = attributes?.request?.remoteAddr
        return if (!remoteAddress.isNullOrBlank()) sanitize(remoteAddress) else "local"
    }

    private fun idempotencyKey(): Optional<String> {
        val attributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes ?: return Optional.empty()
        val value = attributes.request.getHeader("Idempotency-Key")
        return if (!value.isNullOrBlank()) Optional.of(value.trim()) else Optional.empty()
    }

    private fun sanitize(value: String) = value.replace(Regex("[^A-Za-z0-9._:-]"), "_")

    private fun fingerprint(action: String, requestFingerprintSource: Any?): String = try {
        sha256(objectMapper.writeValueAsString(java.util.List.of(action, requestFingerprintSource)))
    } catch (exception: JsonProcessingException) {
        sha256("$action:$requestFingerprintSource")
    }

    private fun sha256(value: String): String = try {
        val digest = MessageDigest.getInstance("SHA-256")
        HexFormat.of().formatHex(digest.digest(value.toByteArray(StandardCharsets.UTF_8)))
    } catch (exception: NoSuchAlgorithmException) {
        throw IllegalStateException("SHA-256 is unavailable", exception)
    }

    private fun key(suffix: String) = properties.keyPrefix + suffix

    @JvmRecord
    private data class CachedHttpResponse(val status: Int, val body: JsonNode?)

    private companion object {
        val log = LoggerFactory.getLogger(RedisRequestGuard::class.java)
        // ponytail: fixed bound on how long a crashed request blocks its key; outlives every synchronous API request.
        val IN_FLIGHT_TTL: Duration = Duration.ofMinutes(2)
    }
}
