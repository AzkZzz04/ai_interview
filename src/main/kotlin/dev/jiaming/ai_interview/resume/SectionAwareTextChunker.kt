package dev.jiaming.ai_interview.resume

import java.util.Locale
import org.springframework.stereotype.Component

@Component
class SectionAwareTextChunker {
    fun chunk(normalizedText: String?): List<TextChunk> = chunk(normalizedText, DEFAULT_MAX_CHARS, DEFAULT_OVERLAP_CHARS)

    fun chunk(normalizedText: String?, maxChars: Int, overlapChars: Int): List<TextChunk> {
        if (normalizedText.isNullOrBlank()) return emptyList()
        require(maxChars >= 400) { "maxChars must be at least 400" }
        require(overlapChars >= 0 && overlapChars < maxChars) {
            "overlapChars must be non-negative and smaller than maxChars"
        }
        return collectSections(normalizedText).flatMap { splitSection(it, maxChars, overlapChars) }
            .mapIndexed { index, chunk -> chunk.copy(index = index) }
    }

    private fun collectSections(normalizedText: String): List<SectionBlock> {
        val sections = mutableListOf<SectionBlock>()
        var currentSection = DEFAULT_SECTION
        var content = StringBuilder()
        for (line in normalizedText.split('\n')) {
            if (isSectionHeading(line)) {
                appendSection(sections, currentSection, content)
                currentSection = canonicalSectionName(line)
                content = StringBuilder()
            } else {
                if (content.isNotEmpty()) content.append('\n')
                content.append(line)
            }
        }
        appendSection(sections, currentSection, content)
        return sections
    }

    private fun appendSection(sections: MutableList<SectionBlock>, section: String, content: StringBuilder) {
        val text = content.toString().trim()
        if (text.isNotBlank()) sections += SectionBlock(section, text)
    }

    private fun splitSection(section: SectionBlock, maxChars: Int, overlapChars: Int): List<TextChunk> {
        if (entrySection(section.name)) {
            val entryChunks = section.content.split(Regex("\\n\\s*\\n"))
                .flatMap { addChunks(section.name, it.trim(), maxChars, overlapChars) }
            if (entryChunks.isNotEmpty()) return entryChunks
        }
        return addChunks(section.name, section.content, maxChars, overlapChars)
    }

    private fun addChunks(section: String, content: String, maxChars: Int, overlapChars: Int): List<TextChunk> {
        if (content.isBlank()) return emptyList()
        val chunks = mutableListOf<TextChunk>()
        var cursor = 0
        while (cursor < content.length) {
            var end = minOf(cursor + maxChars, content.length)
            if (end < content.length) end = findNaturalBreak(content, cursor, end)
            val chunkContent = content.substring(cursor, end).trim()
            if (chunkContent.isNotBlank()) chunks += TextChunk(0, section, chunkContent)
            if (end >= content.length) break
            cursor = maxOf(0, end - overlapChars)
        }
        return chunks
    }

    private fun entrySection(section: String) = section == "Experience" || section == "Research Experience" || section == "Projects"

    private fun findNaturalBreak(content: String, cursor: Int, proposedEnd: Int): Int {
        val paragraphBreak = content.lastIndexOf("\n\n", proposedEnd)
        if (paragraphBreak > cursor + 200) return paragraphBreak
        val lineBreak = content.lastIndexOf('\n', proposedEnd)
        if (lineBreak > cursor + 200) return lineBreak
        val sentenceBreak = content.lastIndexOf(". ", proposedEnd)
        if (sentenceBreak > cursor + 200) return sentenceBreak + 1
        return proposedEnd
    }

    private fun isSectionHeading(line: String): Boolean {
        val cleaned = line.trim()
        if (cleaned.isBlank() || cleaned.length > 64) return false
        val normalized = cleaned.replace(":", "").lowercase(Locale.ROOT).trim()
        return normalized in KNOWN_SECTION_HEADINGS ||
            (cleaned == cleaned.uppercase(Locale.ROOT) && normalized in KNOWN_SECTION_HEADINGS)
    }

    private fun canonicalSectionName(heading: String): String {
        val cleaned = heading.replace(":", "").trim().lowercase(Locale.ROOT)
        return when (cleaned) {
            "work experience", "professional experience", "employment", "work history" -> "Experience"
            "research experience" -> "Research Experience"
            "technical skills", "technologies", "core competencies", "tools and technologies" -> "Skills"
            "project experience", "selected projects", "personal projects", "academic projects", "technical projects" -> "Projects"
            "academic background" -> "Education"
            "coursework", "relevant coursework" -> "Coursework"
            "certificates" -> "Certifications"
            "professional summary", "profile", "objective" -> "Summary"
            else -> cleaned.replaceFirstChar { it.uppercase() }
        }
    }

    private data class SectionBlock(val name: String, val content: String)

    companion object {
        private const val DEFAULT_MAX_CHARS = 1_800
        private const val DEFAULT_OVERLAP_CHARS = 180
        private const val DEFAULT_SECTION = "Summary"
        private val KNOWN_SECTION_HEADINGS = setOf(
            "summary", "professional summary", "profile", "objective", "experience", "work experience",
            "professional experience", "employment", "work history", "research experience", "projects",
            "project experience", "selected projects", "personal projects", "academic projects", "technical projects",
            "skills", "technical skills", "core competencies", "technologies", "tools and technologies", "education",
            "academic background", "coursework", "relevant coursework", "certifications", "certificates", "publications",
            "awards", "leadership", "volunteering"
        )
    }
}
