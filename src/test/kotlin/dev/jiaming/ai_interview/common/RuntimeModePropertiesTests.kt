package dev.jiaming.ai_interview.common

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RuntimeModePropertiesTests {
	@Test
	fun exposesApiAndWorkerCapabilities() {
		assertThat(RuntimeModeProperties("all").apiEnabled()).isTrue()
		assertThat(RuntimeModeProperties("all").workerEnabled()).isTrue()
		assertThat(RuntimeModeProperties("api").workerEnabled()).isFalse()
		assertThat(RuntimeModeProperties("worker").apiEnabled()).isFalse()
	}

	@Test
	fun rejectsUnknownMode() {
		assertThatThrownBy { RuntimeModeProperties("batch") }
			.isInstanceOf(IllegalArgumentException::class.java)
			.hasMessageContaining("all, api, or worker")
	}
}
