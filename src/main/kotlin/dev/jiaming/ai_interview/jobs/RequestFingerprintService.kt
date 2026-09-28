package dev.jiaming.ai_interview.jobs

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

@Component
class RequestFingerprintService(private val objectMapper: ObjectMapper) {
    fun fingerprint(action: String, source: Any): String = try {
        sha256(objectMapper.writeValueAsString(listOf(action, source)))
    } catch (_: JsonProcessingException) {
        sha256("$action:$source")
    }
    private fun sha256(value: String): String = try {
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))
    } catch (exception: java.security.NoSuchAlgorithmException) {
        throw IllegalStateException("SHA-256 is unavailable", exception)
    }
}
