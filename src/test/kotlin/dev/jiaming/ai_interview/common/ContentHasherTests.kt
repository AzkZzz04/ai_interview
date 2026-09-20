package dev.jiaming.ai_interview.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ContentHasherTests {
	private val hasher = ContentHasher()

	@Test
	fun hashesExactUtf8Content() {
		assertThat(hasher.sha256("resume\ntext"))
			.isEqualTo("a4be4224ed5f0f1903d537572da07ee94bbcf1063ad61b6d960825af65db0e9e")
	}
}
