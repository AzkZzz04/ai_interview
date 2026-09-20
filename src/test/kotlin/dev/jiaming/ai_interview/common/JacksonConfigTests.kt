package dev.jiaming.ai_interview.common

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JacksonConfigTests {
	@Test
	fun serializesInstantsAsIsoStringsForPersistedJobResults() {
		val mapper = JacksonConfig().objectMapper()
		val result = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(TimestampResult(Instant.parse("2026-07-17T20:27:17Z")))
		assertThat(result.get("processedAt").asText()).isEqualTo("2026-07-17T20:27:17Z")
	}

	private data class TimestampResult(val processedAt: Instant)
}
