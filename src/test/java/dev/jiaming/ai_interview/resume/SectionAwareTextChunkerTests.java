package dev.jiaming.ai_interview.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class SectionAwareTextChunkerTests {

	private final SectionAwareTextChunker chunker = new SectionAwareTextChunker();

	@Test
	void keepsResumeSectionMetadataOnChunks() {
		String resume = """
			SUMMARY
			Backend engineer focused on distributed systems.

			EXPERIENCE
			Built Java services with Spring Boot and PostgreSQL.

			SKILLS
			Java, Spring Boot, PostgreSQL, Redis
			""";

		List<TextChunk> chunks = chunker.chunk(resume, 500, 50);

		assertThat(chunks).extracting(TextChunk::section)
			.containsExactly("Summary", "Experience", "Skills");
		assertThat(chunks).extracting(TextChunk::index)
			.containsExactly(0, 1, 2);
	}

	@Test
	void recognizesCommonResumeHeadingAliases() {
		String resume = """
			RESEARCH EXPERIENCE
			Studied distributed systems.

			PERSONAL PROJECTS
			Built an interview coach.

			CORE COMPETENCIES
			Java, PostgreSQL, Redis

			RELEVANT COURSEWORK
			Databases and software construction
			""";

		List<TextChunk> chunks = chunker.chunk(resume, 500, 50);

		assertThat(chunks).extracting(TextChunk::section)
			.containsExactly("Research Experience", "Projects", "Skills", "Coursework");
	}

	@Test
	void splitsLongSectionsWithOverlap() {
		String content = "EXPERIENCE\n" + "Built production backend services. ".repeat(80);

		List<TextChunk> chunks = chunker.chunk(content, 500, 80);

		assertThat(chunks).hasSizeGreaterThan(1);
		assertThat(chunks).allSatisfy(chunk -> {
			assertThat(chunk.section()).isEqualTo("Experience");
			assertThat(chunk.content()).isNotBlank();
		});
	}

	@Test
	void rejectsInvalidChunkSettings() {
		assertThatThrownBy(() -> chunker.chunk("text", 399, 10))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> chunker.chunk("text", 500, 500))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
