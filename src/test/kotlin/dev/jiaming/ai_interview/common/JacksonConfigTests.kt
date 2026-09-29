package dev.jiaming.ai_interview.common

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.json.JsonMapper

class JacksonConfigTests {
	@Test
	fun serializesInstantsAsIsoStringsForPersistedJobResults() {
		val mapper = JacksonConfig().objectMapper()
		val result = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(TimestampResult(Instant.parse("2026-07-17T20:27:17Z")))
		assertThat(result.get("processedAt").asText()).isEqualTo("2026-07-17T20:27:17Z")
	}

	@Test
	fun applicationMapperHonorsKotlinConstructorDefaults() {
		val request = JacksonConfig().objectMapper().readValue("""{"name":"a"}""", KotlinRequest::class.java)
		assertThat(request).isEqualTo(KotlinRequest("a", 3))
	}

	@Test
	fun springMvcMapperHonorsKotlinConstructorDefaults() {
		ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration::class.java))
			.run { context ->
				val request = context.getBean(JsonMapper::class.java).readValue("""{"name":"a"}""", KotlinRequest::class.java)
				assertThat(request).isEqualTo(KotlinRequest("a", 3))
			}
	}

	private data class TimestampResult(val processedAt: Instant)

	data class KotlinRequest(val name: String, val limit: Int = 3)
}
