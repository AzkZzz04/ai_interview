package dev.jiaming.ai_interview.interview

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.HexFormat

@Component
class PersistenceJsonSupport(private val objectMapper: ObjectMapper) {
    fun promptName(): String = PROMPT_NAME

    fun json(value: Any?): String = try {
        objectMapper.writeValueAsString(value ?: emptyList<Any>())
    } catch (exception: JsonProcessingException) {
        throw IllegalStateException("Could not serialize persistence payload", exception)
    }

    fun model(modelProvider: String?): String =
        if (modelProvider.isNullOrBlank()) "gemini" else modelProvider

    fun hash(vararg values: String?): String = try {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            digest.update((value ?: "").toByteArray(StandardCharsets.UTF_8))
            digest.update(0.toByte())
        }
        HexFormat.of().formatHex(digest.digest())
    } catch (exception: NoSuchAlgorithmException) {
        throw IllegalStateException("SHA-256 is unavailable", exception)
    }

    private companion object {
        const val PROMPT_NAME = "rag-grounded"
    }
}
