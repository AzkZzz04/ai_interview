package dev.jiaming.ai_interview.common

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.HexFormat
import org.springframework.stereotype.Component

@Component
class ContentHasher {
    fun sha256(normalizedText: String?): String {
        require(normalizedText != null) { "Normalized text is required" }
        try {
            val digest = MessageDigest.getInstance("SHA-256").digest(normalizedText.toByteArray(StandardCharsets.UTF_8))
            return HexFormat.of().formatHex(digest)
        } catch (exception: NoSuchAlgorithmException) {
            throw IllegalStateException("SHA-256 is unavailable", exception)
        }
    }
}
