package dev.jiaming.ai_interview.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContentHasherTests {

	private final ContentHasher hasher = new ContentHasher();

	@Test
	void hashesExactUtf8Content() {
		assertThat(hasher.sha256("resume\ntext"))
			.isEqualTo("a4be4224ed5f0f1903d537572da07ee94bbcf1063ad61b6d960825af65db0e9e");
	}
}
