package dev.jiaming.ai_interview.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class GeminiClientSpringContextTests {

	@Test
	void productionConstructorCanBeAutowiredWhenTestConstructorAlsoExists() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.getBeanFactory().registerSingleton("objectMapper", new ObjectMapper());
			context.getBeanFactory().registerSingleton("meterRegistry", new SimpleMeterRegistry());
			context.register(GeminiClient.class);

			context.refresh();

			assertThat(context.getBean(GeminiClient.class)).isNotNull();
		}
	}
}
