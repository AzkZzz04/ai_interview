package dev.jiaming.ai_interview.resume

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SectionAwareTextChunkerTests {
	private val chunker = SectionAwareTextChunker()

	@Test
	fun keepsResumeSectionMetadataOnChunks() {
		val resume = """
			SUMMARY
			Backend engineer focused on distributed systems.

			EXPERIENCE
			Built Java services with Spring Boot and PostgreSQL.

			SKILLS
			Java, Spring Boot, PostgreSQL, Redis
		""".trimIndent()
		val chunks = chunker.chunk(resume, 500, 50)
		assertThat(chunks.map { it.section() }).containsExactly("Summary", "Experience", "Skills")
		assertThat(chunks.map { it.index() }).containsExactly(0, 1, 2)
	}

	@Test
	fun recognizesCommonResumeHeadingAliases() {
		val resume = """
			RESEARCH EXPERIENCE
			Studied distributed systems.

			PERSONAL PROJECTS
			Built an interview coach.

			CORE COMPETENCIES
			Java, PostgreSQL, Redis

			RELEVANT COURSEWORK
			Databases and software construction
		""".trimIndent()
		assertThat(chunker.chunk(resume, 500, 50).map { it.section() })
			.containsExactly("Research Experience", "Projects", "Skills", "Coursework")
	}

	@Test
	fun splitsLongSectionsWithOverlap() {
		val chunks = chunker.chunk("EXPERIENCE\n" + "Built production backend services. ".repeat(80), 500, 80)
		assertThat(chunks).hasSizeGreaterThan(1)
		chunks.forEach {
			assertThat(it.section()).isEqualTo("Experience")
			assertThat(it.content()).isNotBlank()
		}
	}

	@Test
	fun keepsBlankLineSeparatedExperienceEntriesTogether() {
		val resume = """
			EXPERIENCE
			Backend Engineer, Example Co. | 2024 - Present
			- Built an event-driven service.

			Software Engineer, Prior Co. | 2021 - 2024
			- Improved database reliability.
		""".trimIndent()
		val chunks = chunker.chunk(resume, 500, 50)
		assertThat(chunks).hasSize(2)
		assertThat(chunks.first().content()).contains("Example Co.").doesNotContain("Prior Co.")
		assertThat(chunks[1].content()).contains("Prior Co.").doesNotContain("Example Co.")
	}

	@Test
	fun rejectsInvalidChunkSettings() {
		assertThatThrownBy { chunker.chunk("text", 399, 10) }.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { chunker.chunk("text", 500, 500) }.isInstanceOf(IllegalArgumentException::class.java)
	}
}
