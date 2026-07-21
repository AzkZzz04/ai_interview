package dev.jiaming.ai_interview.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JobPropertiesTests {

	@Test
	void appliesIndependentTransportAndApplicationRetrySettings() {
		JobProperties properties = properties("custom", "custom-dead", 3, 5, 299, 60);

		assertThat(properties.queueName()).isEqualTo("custom");
		assertThat(properties.dlqName()).isEqualTo("custom-dead");
		assertThat(properties.maxReceiveCount()).isEqualTo(3);
		assertThat(properties.maxAttempts()).isEqualTo(5);
	}

	@Test
	void clampsHeartbeatToHalfTheVisibilityTimeout() {
		JobProperties properties = properties("jobs", null, 3, 3, 30, 29);

		assertThat(properties.dlqName()).isEqualTo("jobs-dlq");
		assertThat(properties.heartbeatSeconds()).isEqualTo(15);
	}

	private JobProperties properties(
		String queue,
		String dlq,
		int maxReceiveCount,
		int maxAttempts,
		int visibility,
		int heartbeat
	) {
		return new JobProperties(
			true, "http://localhost:4566", "us-east-1", "test", "test",
			queue, dlq, maxReceiveCount, 2, 20, visibility, heartbeat, maxAttempts,
			15, 300, 5_000, 30_000, 3_600_000, 120, 7
		);
	}
}
