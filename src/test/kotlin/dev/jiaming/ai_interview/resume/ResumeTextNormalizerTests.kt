package dev.jiaming.ai_interview.resume

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ResumeTextNormalizerTests {
	private val normalizer = ResumeTextNormalizer()

	@Test
	fun normalizesWhitespaceAndBlankLines() {
		val raw = "  Jane   Doe\r\n\r\n\r\nSkills\tJava   Spring\r\n  "
		assertThat(normalizer.normalize(raw)).isEqualTo("Jane Doe\n\nSkills Java Spring")
	}

	@Test
	fun returnsEmptyStringForBlankInput() {
		assertThat(normalizer.normalize("   ")).isEmpty()
		assertThat(normalizer.normalize(null)).isEmpty()
	}
}
