package dev.jiaming.ai_interview.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class JobRetryDelayStrategyTests {

	@Test
	void usesFullJitterWithinExponentialCap() {
		AtomicLong observedBound = new AtomicLong();
		JobRetryDelayStrategy strategy = new JobRetryDelayStrategy(bound -> {
			observedBound.set(bound);
			return bound - 1;
		});

		assertThat(strategy.delay(3, 15)).isEqualTo(Duration.ofSeconds(60));
		assertThat(observedBound).hasValue(61);
	}

	@Test
	void capsRetriesAtFiveMinutesAndAllowsZeroDelay() {
		JobRetryDelayStrategy maximum = new JobRetryDelayStrategy(bound -> bound - 1);
		JobRetryDelayStrategy minimum = new JobRetryDelayStrategy(bound -> 0);

		assertThat(maximum.delay(20, 15)).isEqualTo(Duration.ofMinutes(5));
		assertThat(minimum.delay(20, 15)).isZero();
	}
}
