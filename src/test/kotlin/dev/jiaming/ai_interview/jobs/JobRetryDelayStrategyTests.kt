package dev.jiaming.ai_interview.jobs

import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JobRetryDelayStrategyTests {
	@Test
	fun usesFullJitterWithinExponentialCap() {
		val observedBound = AtomicLong()
		val strategy = JobRetryDelayStrategy { bound ->
			observedBound.set(bound)
			bound - 1
		}
		assertThat(strategy.delay(3, 15)).isEqualTo(Duration.ofSeconds(60))
		assertThat(observedBound).hasValue(61)
	}

	@Test
	fun capsRetriesAtFiveMinutesAndAllowsZeroDelay() {
		val maximum = JobRetryDelayStrategy { bound -> bound - 1 }
		val minimum = JobRetryDelayStrategy { 0 }
		assertThat(maximum.delay(20, 15)).isEqualTo(Duration.ofMinutes(5))
		assertThat(minimum.delay(20, 15)).isZero()
	}
}
