package dev.jiaming.ai_interview.jobs;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;

import org.springframework.stereotype.Component;

@Component
public class JobRetryDelayStrategy {

	private static final long MAX_DELAY_SECONDS = 300;

	private final LongUnaryOperator randomBelow;

	public JobRetryDelayStrategy() {
		this(bound -> ThreadLocalRandom.current().nextLong(bound));
	}

	JobRetryDelayStrategy(LongUnaryOperator randomBelow) {
		this.randomBelow = randomBelow;
	}

	public Duration delay(int attempt, int baseSeconds) {
		int exponent = Math.min(20, Math.max(0, attempt - 1));
		long exponential = Math.multiplyExact(Math.max(1L, baseSeconds), 1L << exponent);
		long upperBound = Math.min(MAX_DELAY_SECONDS, exponential);
		return Duration.ofSeconds(randomBelow.applyAsLong(upperBound + 1));
	}
}
