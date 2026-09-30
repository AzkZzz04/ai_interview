package dev.jiaming.ai_interview.supabase

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.jobs.JobErrorResponse
import dev.jiaming.ai_interview.jobs.JobStatus
import io.github.jan.supabase.SupabaseClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.UUID

class SupabaseJobStatusReaderTests {
    private val jobId = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val userId = UUID.fromString("20000000-0000-0000-0000-000000000002")
    private val resourceId = UUID.fromString("30000000-0000-0000-0000-000000000003")
    private val jobDescriptionId = UUID.fromString("40000000-0000-0000-0000-000000000004")

    @Test
    fun `queries owner scoped custom schema and maps raw JSON without inventing timestamps`() = runBlocking {
        val body = row(
            requestPayload = """{"resumeId":"invalid","jobDescriptionId":"$jobDescriptionId"}""",
            resultPayload = """{"overallScore":84,"tags":["clear",null],"details":{"rating":7.5}}"""
        )
        val (client, reader) = reader(body) { request ->
            assertTrue(request.url.encodedPath.endsWith("/rest/v1/job_status"))
            assertEquals("ai_interview_api", request.headers["Accept-Profile"])
            assertEquals("eq.$jobId", request.url.parameters["id"])
            assertEquals("eq.$userId", request.url.parameters["user_id"])
            assertEquals("1", request.url.parameters["limit"])
            assertEquals("id,user_id,job_type,status,stage,attempts,result_payload,last_error,error_code,retryable,created_at,started_at,completed_at,resource_id,request_payload", request.url.parameters["select"])
            assertEquals(KEY, request.headers["apikey"])
            assertNull(request.headers[HttpHeaders.Authorization])
        }
        try {
            val status = reader.findForUser(jobId, userId)!!
            assertEquals(jobId, status.jobId)
            assertEquals(JobStatus.QUEUED, status.status)
            assertEquals(84, (status.result as Map<*, *>)["overallScore"])
            assertEquals(listOf("clear", null), (status.result as Map<*, *>)["tags"])
            assertEquals(7.5, ((status.result as Map<*, *>)["details"] as Map<*, *>)["rating"])
            assertEquals(resourceId, status.inputRefs.resumeId)
            assertEquals(jobDescriptionId, status.inputRefs.jobDescriptionId)
            assertNull(status.createdAt)
            assertNull(status.startedAt)
            assertNull(status.completedAt)
            assertNull(status.error)
        } finally { client.close() }
    }

    @Test
    fun `maps job errors only when an error message is present`() = runBlocking {
        val body = row(lastError = "\"temporary backend failure\"", errorCode = "\"UPSTREAM\"", retryable = "true")
        val (client, reader) = reader(body)
        try {
            assertEquals(JobErrorResponse("UPSTREAM", "temporary backend failure", true), reader.findForUser(jobId, userId)!!.error)
        } finally { client.close() }
    }

    @Test
    fun `empty array stays missing for the controller to return not found`() = runBlocking {
        val (client, reader) = reader("[]")
        try { assertNull(reader.findForUser(jobId, userId)) } finally { client.close() }
    }

    @Test
    fun `malformed payloads and mismatched rows become sanitized service unavailable`() = runBlocking {
        val mismatchedOwner = row(user = UUID.randomUUID())
        val mismatchedJob = row(job = UUID.randomUUID())
        listOf("{", "{}", "[{}]", mismatchedOwner, mismatchedJob).forEach { body ->
            val (client, reader) = reader(body)
            try { assertUnavailable { reader.findForUser(jobId, userId) } } finally { client.close() }
        }
    }

    @Test
    fun `upstream errors and request timeout become sanitized service unavailable`() = runBlocking {
        val failedClient = SupabaseClientConfig.createClient(URL, KEY, MockEngine {
            respond("private upstream detail", HttpStatusCode.ServiceUnavailable, jsonHeaders)
        })
        try { assertUnavailable { SupabaseJobStatusReader(failedClient, ObjectMapper()).findForUser(jobId, userId) } }
        finally { failedClient.close() }

        val slowClient = SupabaseClientConfig.createClient(URL, KEY, MockEngine {
            delay(15_000)
            respond("[]", HttpStatusCode.OK, jsonHeaders)
        })
        try { assertUnavailable { SupabaseJobStatusReader(slowClient, ObjectMapper()).findForUser(jobId, userId) } }
        finally { slowClient.close() }
    }

    private fun reader(body: String, inspectRequest: (io.ktor.client.request.HttpRequestData) -> Unit = {}): Pair<SupabaseClient, SupabaseJobStatusReader> {
        val client = SupabaseClientConfig.createClient(URL, KEY, MockEngine { request ->
            inspectRequest(request)
            respond(body, HttpStatusCode.OK, jsonHeaders)
        })
        return client to SupabaseJobStatusReader(client, ObjectMapper())
    }

    private fun row(
        job: UUID = jobId,
        user: UUID = userId,
        requestPayload: String = "{}",
        resultPayload: String = "null",
        lastError: String = "null",
        errorCode: String = "\"STALE_CODE\"",
        retryable: String = "true"
    ) = """[{"id":"$job","user_id":"$user","job_type":"ANALYSIS","status":"QUEUED","stage":"QUEUED","attempts":2,"result_payload":$resultPayload,"last_error":$lastError,"error_code":$errorCode,"retryable":$retryable,"created_at":null,"started_at":null,"completed_at":null,"resource_id":"$resourceId","request_payload":$requestPayload}]"""

    private fun assertUnavailable(action: () -> Any?) {
        val exception = assertFailsWith<ApiRequestException> { action() }
        assertEquals(HttpStatusCode.ServiceUnavailable.value, exception.status().value())
        assertEquals("SERVICE_UNAVAILABLE", exception.code())
        assertEquals("Job status is temporarily unavailable", exception.message)
        assertNull(exception.cause)
        assertFalse(exception.message.orEmpty().contains("private upstream detail"))
        assertFalse(exception.message.orEmpty().contains(KEY))
    }

    private companion object {
        const val URL = "https://example.supabase.co"
        const val KEY = "sb_secret_test"
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
