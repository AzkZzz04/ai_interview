package dev.jiaming.ai_interview.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

class JobHandlerRegistryTests {

	@Test
	void requiresExactlyOneHandlerForEveryJobType() {
		JobHandler<?> resume = handler(JobType.RESUME_EXTRACTION);
		JobHandler<?> analysis = handler(JobType.ANALYSIS);
		JobHandler<?> feedback = handler(JobType.ANSWER_FEEDBACK);

		JobHandlerRegistry registry = new JobHandlerRegistry(List.of(resume, analysis, feedback));

		assertThat(registry.require(JobType.ANALYSIS)).isSameAs(analysis);
	}

	@Test
	void rejectsDuplicateHandlers() {
		assertThatThrownBy(() -> new JobHandlerRegistry(List.of(
			handler(JobType.RESUME_EXTRACTION),
			handler(JobType.ANALYSIS),
			handler(JobType.ANALYSIS),
			handler(JobType.ANSWER_FEEDBACK)
		))).isInstanceOf(IllegalStateException.class).hasMessageContaining("Multiple job handlers");
	}

	@Test
	void rejectsMissingHandlers() {
		assertThatThrownBy(() -> new JobHandlerRegistry(List.of(handler(JobType.ANALYSIS))))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Missing job handlers");
	}

	private JobHandler<Object> handler(JobType type) {
		return new JobHandler<>() {
			@Override
			public JobType type() {
				return type;
			}

			@Override
			public Class<Object> payloadType() {
				return Object.class;
			}

			@Override
			public JsonNode handle(Object payload, JobExecutionContext context) {
				return null;
			}
		};
	}
}
