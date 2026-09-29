package dev.jiaming.ai_interview.rag

import dev.jiaming.ai_interview.resume.TextChunk
import java.util.Locale

object RagContextId {
    @JvmStatic fun forChunk(sourceType: String, chunk: TextChunk) = forChunk(sourceType, chunk.section, chunk.index)
    @JvmStatic fun forChunk(sourceType: String, section: String?, chunkIndex: Int) =
        "${normalizeSourceType(sourceType)}:${slug(section, "section")}:$chunkIndex"
    private fun normalizeSourceType(sourceType: String) = slug(sourceType, "source").replace('-', '_')
    private fun slug(value: String?, fallback: String): String {
        if (value.isNullOrBlank()) return fallback
        val slug = value.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").replace(Regex("(^-|-$)"), "")
        return slug.ifBlank { fallback }
    }
}
