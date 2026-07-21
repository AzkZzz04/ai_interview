package dev.jiaming.ai_interview.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RuntimeModePropertiesTests {

	@Test
	void exposesApiAndWorkerCapabilities() {
		assertThat(new RuntimeModeProperties("all").apiEnabled()).isTrue();
		assertThat(new RuntimeModeProperties("all").workerEnabled()).isTrue();
		assertThat(new RuntimeModeProperties("api").workerEnabled()).isFalse();
		assertThat(new RuntimeModeProperties("worker").apiEnabled()).isFalse();
	}

	@Test
	void rejectsUnknownMode() {
		assertThatThrownBy(() -> new RuntimeModeProperties("batch"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("all, api, or worker");
	}
}
