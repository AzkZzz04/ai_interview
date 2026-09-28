package dev.jiaming.ai_interview.resume

import org.springframework.stereotype.Component

@Component
class ResumeTextNormalizer {
    fun normalize(rawText: String?): String {
        if (rawText.isNullOrBlank()) return ""
        val normalizedLines = rawText.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            .joinToString("\n") { normalizeLine(it) }
        return normalizedLines.replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private fun normalizeLine(line: String) = line.replace('\t', ' ').replace(Regex("\\s{2,}"), " ").trim()
}
