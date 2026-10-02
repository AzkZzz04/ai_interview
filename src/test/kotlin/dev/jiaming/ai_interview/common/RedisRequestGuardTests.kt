package dev.jiaming.ai_interview.common

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.json.JsonMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisRequestGuardTests {

    private val redis = ConcurrentHashMap<String, String>()
    private val expiresAt = ConcurrentHashMap<String, Instant>()

    @Mock
    private lateinit var redisTemplate: StringRedisTemplate

    @Mock
    private lateinit var valueOperations: ValueOperations<String, String>

    private lateinit var guard: RedisRequestGuard

    @BeforeEach
    fun setUp() {
        Mockito.`when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        Mockito.`when`(valueOperations.get(anyString())).thenAnswer { getValue(it.getArgument(0)) }
        Mockito.`when`(valueOperations.setIfAbsent(anyString(), anyString(), any<Duration>()))
            .thenAnswer { setIfAbsent(it.getArgument(0), it.getArgument(1), it.getArgument(2)) }
        Mockito.doAnswer {
            setValue(it.getArgument(0), it.getArgument(1), it.getArgument(2))
            null
        }.`when`(valueOperations).set(anyString(), anyString(), any<Duration>())
        Mockito.`when`(valueOperations.increment(anyString())).thenAnswer {
            val key = it.getArgument<String>(0)
            val nextValue = (getValue(key) ?: "0").toLong() + 1
            redis[key] = nextValue.toString()
            nextValue
        }
        Mockito.`when`(redisTemplate.expire(anyString(), any<Duration>())).thenAnswer {
            val key = it.getArgument<String>(0)
            if (getValue(key) != null) expiresAt[key] = Instant.now().plus(it.getArgument<Duration>(1))
            true
        }
        Mockito.`when`(redisTemplate.delete(anyString())).thenAnswer { removeValue(it.getArgument<String>(0)) != null }
        stubScriptExecution(::executeScript)
        guard = RedisRequestGuard(
            redisTemplate,
            RedisUsageProperties(
                RedisUsageProperties.RateLimit(true, 60, 2, 2),
                RedisUsageProperties.Idempotency(true, 86_400)
            ),
            objectMapper()
        )
    }

    @AfterEach
    fun tearDown() = RequestContextHolder.resetRequestAttributes()

    private fun executeScript(script: RedisScript<Long>, key: String, args: Array<Any>): Long {
        val expected = args[0] as String
        if (getValue(key) != expected) return 0L
        return when {
            script.scriptAsString.contains("PEXPIRE") -> {
                expiresAt[key] = Instant.now().plusMillis((args[1] as String).toLong())
                1L
            }
            script.scriptAsString.contains("'PX'") -> {
                setValue(key, args[1] as String, Duration.ofMillis((args[2] as String).toLong()))
                1L
            }
            script.scriptAsString.contains("'DEL'") -> if (removeValue(key) != null) 1L else 0L
            else -> error("Unexpected Redis script")
        }
    }

    private fun stubScriptExecution(handler: (RedisScript<Long>, String, Array<Any>) -> Long) {
        val answer = org.mockito.stubbing.Answer<Long> {
            val script = it.getArgument<RedisScript<Long>>(0)
            val key = it.getArgument<List<String>>(1).single()
            handler(script, key, it.rawArguments[2] as Array<Any>)
        }
        Mockito.doAnswer(answer).`when`(redisTemplate).execute(
            any<RedisScript<Long>>(), Mockito.anyList<String>(), any<String>()
        )
        Mockito.doAnswer(answer).`when`(redisTemplate).execute(
            any<RedisScript<Long>>(), Mockito.anyList<String>(), any<String>(), any<String>()
        )
        Mockito.doAnswer(answer).`when`(redisTemplate).execute(
            any<RedisScript<Long>>(), Mockito.anyList<String>(), any<String>(), any<String>(), any<String>()
        )
    }

    private fun getValue(key: String): String? {
        if (expiresAt[key]?.let { !it.isAfter(Instant.now()) } == true) removeValue(key)
        return redis[key]
    }

    private fun setIfAbsent(key: String, value: String, ttl: Duration): Boolean {
        if (getValue(key) != null || redis.putIfAbsent(key, value) != null) return false
        expiresAt[key] = Instant.now().plus(ttl)
        return true
    }

    private fun setValue(key: String, value: String, ttl: Duration) {
        redis[key] = value
        expiresAt[key] = Instant.now().plus(ttl)
    }

    private fun removeValue(key: String): String? {
        expiresAt.remove(key)
        return redis.remove(key)
    }

    @Test
    fun replaysCachedResponseForSameIdempotencyKeyAndPayload() {
        requestWithIdempotencyKey("retry-key")
        val calls = AtomicInteger()
        val first = guard.withIdempotentRetryCache("assessment", listOf("resume", "job"), CachedResponse::class.java) {
            CachedResponse("run-${calls.incrementAndGet()}")
        }
        val second = guard.withIdempotentRetryCache("assessment", listOf("resume", "job"), CachedResponse::class.java) {
            CachedResponse("run-${calls.incrementAndGet()}")
        }

        assertThat(first).isEqualTo(CachedResponse("run-1"))
        assertThat(second).isEqualTo(first)
        assertThat(calls).hasValue(1)
    }

    @Test
    fun rejectsSameKeyRetryWhileFirstRequestIsInFlight() {
        requestWithIdempotencyKey("retry-key")
        val calls = AtomicInteger()
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val firstRequest = executor.submit<CachedResponse> {
            requestWithIdempotencyKey("retry-key")
            guard.withIdempotentRetryCache("assessment", listOf("resume"), CachedResponse::class.java) {
                calls.incrementAndGet()
                firstStarted.countDown()
                check(releaseFirst.await(5, TimeUnit.SECONDS))
                CachedResponse("first")
            }
        }

        var retryError: Throwable? = null
        try {
            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue()
            try {
                guard.withIdempotentRetryCache("assessment", listOf("resume"), CachedResponse::class.java) {
                    calls.incrementAndGet()
                    CachedResponse("duplicate")
                }
            } catch (exception: Throwable) {
                retryError = exception
            }
        } finally {
            releaseFirst.countDown()
            executor.shutdown()
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }

        assertThat(firstRequest.get(5, TimeUnit.SECONDS)).isEqualTo(CachedResponse("first"))
        assertThat(retryError).isInstanceOf(ResponseStatusException::class.java)
        assertThat((retryError as ResponseStatusException).statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(calls).hasValue(1)

        val replay = guard.withIdempotentRetryCache("assessment", listOf("resume"), CachedResponse::class.java) {
            calls.incrementAndGet()
            CachedResponse("duplicate")
        }
        assertThat(replay).isEqualTo(CachedResponse("first"))
        assertThat(calls).hasValue(1)
    }

    @Test
    fun aStaleSuccessCannotOverwriteItsSuccessorsCachedResponse() = staleRequestCannotChangeSuccessor(fail = false)

    @Test
    fun aStaleFailureCannotDeleteItsSuccessorsCachedResponse() = staleRequestCannotChangeSuccessor(fail = true)

    private fun staleRequestCannotChangeSuccessor(fail: Boolean) {
        requestWithIdempotencyKey("retry-key")
        val calls = AtomicInteger()
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val firstRequest = executor.submit<CachedResponse> {
            requestWithIdempotencyKey("retry-key")
            guard.withIdempotentRetryCache("assessment", listOf("resume"), CachedResponse::class.java) {
                calls.incrementAndGet()
                firstStarted.countDown()
                check(releaseFirst.await(5, TimeUnit.SECONDS))
                if (fail) throw IllegalStateException("stale failure")
                CachedResponse("stale")
            }
        }

        try {
            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue()
            redis.clear() // Simulate A's reservation TTL expiring before its work completes.
            expiresAt.clear()
            val successor = guard.withIdempotentRetryCache("assessment", listOf("resume"), CachedResponse::class.java) {
                calls.incrementAndGet()
                CachedResponse("successor")
            }
            assertThat(successor).isEqualTo(CachedResponse("successor"))
        } finally {
            releaseFirst.countDown()
            executor.shutdown()
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }

        if (fail) {
            assertThatThrownBy { firstRequest.get(5, TimeUnit.SECONDS) }
                .hasCauseInstanceOf(IllegalStateException::class.java)
        } else {
            assertThat(firstRequest.get(5, TimeUnit.SECONDS)).isEqualTo(CachedResponse("stale"))
        }
        val replay = guard.withIdempotentRetryCache("assessment", listOf("resume"), CachedResponse::class.java) {
            calls.incrementAndGet()
            CachedResponse("unexpected")
        }
        assertThat(replay).isEqualTo(CachedResponse("successor"))
        assertThat(calls).hasValue(2)
    }

    @Test
    fun heartbeatKeepsAnActiveReservationOwnedPastItsInitialTtl() {
        guard.inFlightTtl = Duration.ofMillis(1_200)
        guard.heartbeatInterval = Duration.ofMillis(200)
        requestWithIdempotencyKey("retry-key")
        val calls = AtomicInteger()
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val firstRequest = executor.submit<CachedResponse> {
            requestWithIdempotencyKey("retry-key")
            guard.withIdempotentRetryCache("assessment", listOf("resume"), CachedResponse::class.java) {
                calls.incrementAndGet()
                firstStarted.countDown()
                check(releaseFirst.await(5, TimeUnit.SECONDS))
                CachedResponse("first")
            }
        }

        try {
            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue()
            Thread.sleep(1_600)
            assertThatThrownBy {
                guard.withIdempotentRetryCache("assessment", listOf("resume"), CachedResponse::class.java) {
                    calls.incrementAndGet()
                    CachedResponse("duplicate")
                }
            }.isInstanceOfSatisfying(ResponseStatusException::class.java) {
                assertThat(it.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            }
            assertThat(calls).hasValue(1)
        } finally {
            releaseFirst.countDown()
            executor.shutdown()
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }

        assertThat(firstRequest.get(5, TimeUnit.SECONDS)).isEqualTo(CachedResponse("first"))
        val replay = guard.withIdempotentRetryCache("assessment", listOf("resume"), CachedResponse::class.java) {
            calls.incrementAndGet()
            CachedResponse("unexpected")
        }
        assertThat(replay).isEqualTo(CachedResponse("first"))
        assertThat(calls).hasValue(1)
    }

    @Test
    fun aFailedFirstRequestReleasesItsKeySoTheRetryRuns() {
        requestWithIdempotencyKey("retry-key")
        val calls = AtomicInteger()
        assertThatThrownBy {
            guard.withIdempotentRetryCache("assessment", listOf("resume"), CachedResponse::class.java) {
                calls.incrementAndGet()
                throw IllegalStateException("job insert failed")
            }
        }.isInstanceOf(IllegalStateException::class.java)

        val retried = guard.withIdempotentRetryCache("assessment", listOf("resume"), CachedResponse::class.java) {
            CachedResponse("run-${calls.incrementAndGet()}")
        }

        assertThat(retried).isEqualTo(CachedResponse("run-2"))
    }

    @Test
    fun aResponseThatCouldNotBeStoredDoesNotLeaveTheKeyReportingInFlight() {
        requestWithIdempotencyKey("retry-key")
        stubScriptExecution { script, key, args ->
            if (script.scriptAsString.contains("'PX'")) throw IllegalStateException("redis write failed")
            executeScript(script, key, args)
        }
        val calls = AtomicInteger()

        val first = guard.withIdempotentRetryCache("assessment", listOf("resume"), CachedResponse::class.java) {
            CachedResponse("run-${calls.incrementAndGet()}")
        }
        val retry = guard.withIdempotentRetryCache("assessment", listOf("resume"), CachedResponse::class.java) {
            CachedResponse("run-${calls.incrementAndGet()}")
        }

        assertThat(first).isEqualTo(CachedResponse("run-1"))
        assertThat(retry).isEqualTo(CachedResponse("run-2"))
    }

    @Test
    fun theResponseAndFingerprintAreStoredByOneOwnerCheckedWrite() {
        requestWithIdempotencyKey("retry-key")
        val writes = AtomicInteger()
        stubScriptExecution { script, key, args ->
            if (script.scriptAsString.contains("'PX'") && writes.incrementAndGet() > 1) {
                throw IllegalStateException("redis write failed")
            }
            executeScript(script, key, args)
        }
        val calls = AtomicInteger()

        val first = guard.withIdempotentRetryCache("assessment", listOf("resume"), CachedResponse::class.java) {
            CachedResponse("run-${calls.incrementAndGet()}")
        }
        val retry = guard.withIdempotentRetryCache("assessment", listOf("resume"), CachedResponse::class.java) {
            CachedResponse("run-${calls.incrementAndGet()}")
        }

        assertThat(retry).isEqualTo(first)
        assertThat(calls).hasValue(1)
        assertThat(writes).hasValue(1)
    }

    @Test
    fun aReservationReleasedBetweenTheReplaysTwoReadsIsTakenAgainBeforeTheWorkRuns() {
        requestWithIdempotencyKey("retry-key")
        val reserveAttempts = AtomicInteger()
        Mockito.`when`(valueOperations.setIfAbsent(anyString(), anyString(), any<Duration>())).thenAnswer {
            // The first SETNX sees another request's key, which that request releases before this one reads it.
            reserveAttempts.incrementAndGet() > 1 && redis.putIfAbsent(it.getArgument(0), it.getArgument(1)) == null
        }

        val response = guard.withIdempotentRetryCache("assessment", listOf("resume"), CachedResponse::class.java) {
            assertThat(redis.keys).hasSize(1)
            CachedResponse("reserved")
        }

        assertThat(response).isEqualTo(CachedResponse("reserved"))
        assertThat(reserveAttempts).hasValue(2)
    }

    @Test
    fun aKeyThatKeepsChangingHandsReturnsARetryableErrorWithoutRunningTheWork() {
        requestWithIdempotencyKey("retry-key")
        Mockito.`when`(valueOperations.setIfAbsent(anyString(), anyString(), any<Duration>())).thenReturn(false)
        val calls = AtomicInteger()

        assertThatThrownBy {
            guard.withIdempotentRetryCache("assessment", listOf("resume"), CachedResponse::class.java) {
                CachedResponse("run-${calls.incrementAndGet()}")
            }
        }.isInstanceOfSatisfying(ResponseStatusException::class.java) {
            assertThat(it.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        }
        assertThat(calls).hasValue(0)
    }

    @Test
    fun rejectsSameIdempotencyKeyForDifferentPayload() {
        requestWithIdempotencyKey("retry-key")
        guard.withIdempotentRetryCache("assessment", listOf("resume-a"), CachedResponse::class.java) { CachedResponse("saved") }

        assertThatThrownBy {
            guard.withIdempotentRetryCache("assessment", listOf("resume-b"), CachedResponse::class.java) {
                CachedResponse("should-not-run")
            }
        }.isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("409 CONFLICT")
            .hasMessageContaining("Idempotency-Key")
    }

    @Test
    fun replaysCachedStatusAndBodyForSameIdempotencyKeyAndPayload() {
        requestWithIdempotencyKey("create-key")
        val calls = AtomicInteger()
        val first = guard.withIdempotentHttpCache("resume-paste", listOf("text"), CachedResponse::class.java) {
            ResponseEntity.status(HttpStatus.CREATED).body(CachedResponse("run-${calls.incrementAndGet()}"))
        }
        val second = guard.withIdempotentHttpCache("resume-paste", listOf("text"), CachedResponse::class.java) {
            ResponseEntity.ok(CachedResponse("run-${calls.incrementAndGet()}"))
        }

        assertThat(first.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(second.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(second.body).isEqualTo(CachedResponse("run-1"))
        assertThat(calls).hasValue(1)
        assertThatThrownBy {
            guard.withIdempotentHttpCache("resume-paste", listOf("other text"), CachedResponse::class.java) {
                ResponseEntity.ok(CachedResponse("should-not-run"))
            }
        }.isInstanceOf(ResponseStatusException::class.java).hasMessageContaining("409 CONFLICT")
    }

    @Test
    fun bypassesIdempotencyCacheWhenHeaderIsMissing() {
        requestWithIdempotencyKey(null)
        val calls = AtomicInteger()
        val first = guard.withIdempotentRetryCache("assessment", listOf("resume"), CachedResponse::class.java) {
            CachedResponse("run-${calls.incrementAndGet()}")
        }
        val second = guard.withIdempotentRetryCache("assessment", listOf("resume"), CachedResponse::class.java) {
            CachedResponse("run-${calls.incrementAndGet()}")
        }

        assertThat(first).isEqualTo(CachedResponse("run-1"))
        assertThat(second).isEqualTo(CachedResponse("run-2"))
        assertThat(calls).hasValue(2)
    }

    @Test
    fun rateLimitsAiRequestsPerClientBucket() {
        requestWithIdempotencyKey(null)
        guard.assertAiAllowed("assessment")
        guard.assertAiAllowed("assessment")

        assertThatThrownBy { guard.assertAiAllowed("assessment") }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("429 TOO_MANY_REQUESTS")
            .hasMessageContaining("Too many assessment requests")
    }

    @Test
    fun rateLimitsUploadRequestsSeparatelyFromAiRequests() {
        requestWithIdempotencyKey(null)
        guard.assertUploadAllowed()
        guard.assertUploadAllowed()

        assertThatThrownBy { guard.assertUploadAllowed() }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("429 TOO_MANY_REQUESTS")
            .hasMessageContaining("resume-upload")
    }

    @Test
    fun namespacesRateLimitAndIdempotencyKeys() {
        requestWithIdempotencyKey("retry-key")
        guard.assertAiAllowed("assessment")
        guard.withIdempotentRetryCache("assessment", listOf("resume"), CachedResponse::class.java) { CachedResponse("saved") }

        assertThat(redis.keys).allMatch { it.startsWith("ai-interview:v3:") }
    }

    @Test
    fun doesNotTrustClientSuppliedForwardedAddressForRateLimits() {
        val first = MockHttpServletRequest().apply {
            remoteAddr = "203.0.113.10"
            addHeader("X-Forwarded-For", "198.51.100.1")
        }
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(first))
        guard.assertAiAllowed("assessment")
        val second = MockHttpServletRequest().apply {
            remoteAddr = "203.0.113.10"
            addHeader("X-Forwarded-For", "198.51.100.2")
        }
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(second))
        guard.assertAiAllowed("assessment")

        assertThatThrownBy { guard.assertAiAllowed("assessment") }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("429 TOO_MANY_REQUESTS")
    }

    @Test
    fun doesNotTouchRedisWhenIdempotencyIsDisabled() {
        requestWithIdempotencyKey("retry-key")
        val disabledGuard = RedisRequestGuard(
            redisTemplate,
            RedisUsageProperties(
                RedisUsageProperties.RateLimit(true, 60, 2, 2),
                RedisUsageProperties.Idempotency(false, 86_400)
            ),
            objectMapper()
        )

        val response = disabledGuard.withIdempotentRetryCache(
            "assessment", listOf("resume"), CachedResponse::class.java
        ) { CachedResponse("uncached") }

        assertThat(response).isEqualTo(CachedResponse("uncached"))
        Mockito.verifyNoInteractions(valueOperations)
    }

    private fun requestWithIdempotencyKey(idempotencyKey: String?) {
        val request = MockHttpServletRequest().apply {
            remoteAddr = "203.0.113.10"
            if (idempotencyKey != null) addHeader("Idempotency-Key", idempotencyKey)
        }
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
    }

    private fun objectMapper(): ObjectMapper = JsonMapper.builder().findAndAddModules().build()

    data class CachedResponse @JsonCreator constructor(@param:JsonProperty("value") val value: String)
}
