package dev.jiaming.ai_interview.common;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JacksonConfigTests {

	@Test
	void serializesInstantsAsIsoStringsForPersistedJobResults() {
		var mapper = new JacksonConfig().objectMapper();

		var result = mapper.valueToTree(new TimestampResult(Instant.parse("2026-07-17T20:27:17Z")));

		assertThat(result.get("processedAt").asText()).isEqualTo("2026-07-17T20:27:17Z");
	}

	private record TimestampResult(Instant processedAt) {
	}
}
