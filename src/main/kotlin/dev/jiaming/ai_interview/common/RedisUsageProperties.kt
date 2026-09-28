package dev.jiaming.ai_interview.common

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.ConstructorBinding

@ConfigurationProperties(prefix = "app.redis")
class RedisUsageProperties @ConstructorBinding constructor(
    keyPrefix: String?,
    rateLimit: RateLimit?,
    idempotency: Idempotency?
) {
    val keyPrefix: String = normalizedPrefix(keyPrefix)
    val rateLimit: RateLimit = rateLimit ?: RateLimit(true, 60, 12, 20)
    val idempotency: Idempotency = idempotency ?: Idempotency(true, 86_400)

    constructor(rateLimit: RateLimit?, idempotency: Idempotency?) : this("ai-interview:v3:", rateLimit, idempotency)

    class RateLimit(enabled: Boolean, windowSeconds: Int, aiLimit: Int, uploadLimit: Int) {
        val enabled = enabled
        val windowSeconds = if (windowSeconds <= 0) 60 else windowSeconds
        val aiLimit = if (aiLimit <= 0) 12 else aiLimit
        val uploadLimit = if (uploadLimit <= 0) 20 else uploadLimit

    }

    class Idempotency(enabled: Boolean, ttlSeconds: Int) {
        val enabled = enabled
        val ttlSeconds = if (ttlSeconds <= 0) 86_400 else ttlSeconds

    }

    private companion object {
        fun normalizedPrefix(value: String?): String {
            val prefix = if (value.isNullOrBlank()) "ai-interview:v3:" else value.trim()
            return if (prefix.endsWith(":")) prefix else "$prefix:"
        }
    }
}
