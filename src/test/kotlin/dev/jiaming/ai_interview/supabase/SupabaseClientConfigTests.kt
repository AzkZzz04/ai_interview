package dev.jiaming.ai_interview.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.test.*

class SupabaseClientConfigTests {
    private val url = "https://example.supabase.co"
    private val key = "sb_secret_test"
    private val context = ApplicationContextRunner().withUserConfiguration(SupabaseClientConfig::class.java)

    @Test
    fun `SDK is opt in and disabled for workers`() {
        context.run { assertTrue(it.getBeansOfType(SupabaseClient::class.java).isEmpty()) }
        context.withPropertyValues("app.supabase.enabled=true", "app.runtime.mode=worker").run {
            assertNull(it.startupFailure)
            assertTrue(it.getBeansOfType(SupabaseClient::class.java).isEmpty())
        }
        context.withPropertyValues("app.supabase.enabled=true").run { assertNotNull(it.startupFailure) }
        context.withPropertyValues("app.supabase.enabled=true", "app.supabase.url=$url", "app.supabase.secret-key=$key").run {
            assertNull(it.startupFailure)
            assertEquals(1, it.getBeansOfType(SupabaseClient::class.java).size)
        }
    }

    @Test
    fun `rejects unsafe origins and non secret keys`() {
        listOf("", "http://example.supabase.co", "$url/path", "$url?key=secret", "https://user@example.supabase.co").forEach {
            assertFailsWith<IllegalArgumentException> { SupabaseClientConfig.createClient(it, key) }
        }
        listOf("", "sb_publishable_test", "sb_secret_", "sb_secret_bad\n").forEach {
            assertFailsWith<IllegalArgumentException> { SupabaseClientConfig.createClient(url, it) }
        }
    }

    @Test
    fun `sends secret only in apikey and returns raw JSON`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(key, request.headers["apikey"])
            assertNull(request.headers[HttpHeaders.Authorization])
            respond("[{\"id\":\"test\",\"result\":null}]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = SupabaseClientConfig.createClient(url, key, engine)
        try {
            assertEquals("[{\"id\":\"test\",\"result\":null}]", client.postgrest.from("job_status").select().data)
        } finally { client.close() }
    }

    @Test
    fun `does not retry failures or follow redirects`() = runBlocking {
        for (status in listOf(HttpStatusCode.ServiceUnavailable, HttpStatusCode.Found)) {
            var requests = 0
            val engine = MockEngine {
                requests++
                respond("{}", status, headersOf(HttpHeaders.Location, "https://other.example/job_status"))
            }
            val client = SupabaseClientConfig.createClient(url, key, engine)
            try {
                runCatching { client.postgrest.from("job_status").select() }
                assertEquals(1, requests)
            } finally { client.close() }
        }
    }

    @Test
    fun `request times out after five seconds`() = runBlocking {
        var requests = 0
        val client = SupabaseClientConfig.createClient(url, key, MockEngine {
            requests++
            delay(15_000)
            respond("[]")
        })
        val start = System.nanoTime()
        try {
            assertFails { client.postgrest.from("job_status").select() }
            val elapsed = (System.nanoTime() - start) / 1_000_000
            assertTrue(elapsed in 4_000..10_000, "Expected five-second timeout, got ${elapsed}ms")
            assertEquals(1, requests)
        } finally { client.close() }
    }
}
