package dev.jiaming.ai_interview.jobs

import java.time.Duration
import java.util.concurrent.ThreadLocalRandom
import java.util.function.LongUnaryOperator
import org.springframework.stereotype.Component

@Component
class JobRetryDelayStrategy private constructor(private val randomBelow: LongUnaryOperator, marker: Unit) {
    constructor() : this(LongUnaryOperator { bound -> ThreadLocalRandom.current().nextLong(bound) }, Unit)
    internal constructor(randomBelow: LongUnaryOperator) : this(randomBelow, Unit)

    fun delay(attempt: Int, baseSeconds: Int): Duration {
        val exponent = minOf(20, maxOf(0, attempt - 1))
        val exponential = Math.multiplyExact(maxOf(1L, baseSeconds.toLong()), 1L shl exponent)
        val upperBound = minOf(MAX_DELAY_SECONDS, exponential)
        return Duration.ofSeconds(randomBelow.applyAsLong(upperBound + 1))
    }

    companion object { private const val MAX_DELAY_SECONDS = 300L }
}
