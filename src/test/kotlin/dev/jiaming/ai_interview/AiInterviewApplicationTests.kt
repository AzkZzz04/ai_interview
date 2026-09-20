package dev.jiaming.ai_interview

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AiInterviewApplicationTests {
	@Test
	fun applicationClassIsLoadable() {
		assertThat(AiInterviewApplication::class.java).isNotNull()
	}
}
