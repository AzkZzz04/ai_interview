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
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisRequestGuardTests {

    private val redis = ConcurrentHashMap<String, String>()

    @Mock
    private lateinit var redisTemplate: StringRedisTemplate

    @Mock
    private lateinit var valueOperations: ValueOperations<String, String>

    private lateinit var guard: RedisRequestGuard

    @BeforeEach
    fun setUp() {
        Mockito.`when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        Mockito.`when`(valueOperations.get(anyString())).thenAnswer { redis[it.getArgument(0)] }
        Mockito.`when`(valueOperations.setIfAbsent(anyString(), anyString(), any<Duration>()))
            .thenAnswer { redis.putIfAbsent(it.getArgument(0), it.getArgument(1)) == null }
        Mockito.doAnswer {
            redis[it.getArgument(0)] = it.getArgument(1)
            null
        }.`when`(valueOperations).set(anyString(), anyString(), any<Duration>())
        Mockito.`when`(valueOperations.increment(anyString())).thenAnswer {
            val key = it.getArgument<String>(0)
            val nextValue = redis.getOrDefault(key, "0").toLong() + 1
            redis[key] = nextValue.toString()
            nextValue
        }
        Mockito.`when`(redisTemplate.expire(anyString(), any<Duration>())).thenReturn(true)
        Mockito.`when`(redisTemplate.delete(anyString())).thenAnswer { redis.remove(it.getArgument<String>(0)) != null }
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
        Mockito.doThrow(IllegalStateException("redis write failed"))
            .`when`(valueOperations).set(anyString(), anyString(), any<Duration>())
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
    fun theResponseIsStoredWithItsFingerprintInOneWriteSoAFailedSecondWriteCannotLoseIt() {
        requestWithIdempotencyKey("retry-key")
        val writes = AtomicInteger()
        Mockito.doAnswer {
            if (writes.incrementAndGet() > 1) throw IllegalStateException("redis write failed")
            redis[it.getArgument(0)] = it.getArgument(1)
            null
        }.`when`(valueOperations).set(anyString(), anyString(), any<Duration>())
        val calls = AtomicInteger()

        val first = guard.withIdempotentRetryCache("assessment", listOf("resume"), CachedResponse::class.java) {
            CachedResponse("run-${calls.incrementAndGet()}")
        }
        val retry = guard.withIdempotentRetryCache("assessment", listOf("resume"), CachedResponse::class.java) {
            CachedResponse("run-${calls.incrementAndGet()}")
        }

        assertThat(retry).isEqualTo(first)
        assertThat(calls).hasValue(1)
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
