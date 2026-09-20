package dev.jiaming.ai_interview.gemini

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext

class GeminiClientSpringContextTests {
	@Test
	fun productionConstructorCanBeAutowiredWhenTestConstructorAlsoExists() {
		AnnotationConfigApplicationContext().use { context ->
			context.beanFactory.registerSingleton("objectMapper", ObjectMapper())
			context.beanFactory.registerSingleton("meterRegistry", SimpleMeterRegistry())
			context.register(GeminiClient::class.java)
			context.refresh()
			assertThat(context.getBean(GeminiClient::class.java)).isNotNull()
		}
	}
}
