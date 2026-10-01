package dev.jiaming.ai_interview.supabase

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.jobs.JobStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.mock.env.MockEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Properties
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@EnabledIfEnvironmentVariable(named = "SUPABASE_LIVE_SMOKE", matches = "true")
class SupabaseLiveSmokeTests {
    @Test
    fun liveJdbcTransitionsAreVisibleThroughOwnerScopedSdkPolling() {
        val settings = Properties().apply {
            Files.newInputStream(Path.of(System.getenv("SUPABASE_ENV_FILE") ?: ".env.supabase")).use(::load)
        }
        require(settings.getProperty("SUPABASE_URL") == "https://mjzycnjhtwyqcblwbjvy.supabase.co")
        val environment = MockEnvironment()
            .withProperty("app.supabase.url", settings.getProperty("SUPABASE_URL"))
            .withProperty("app.supabase.secret-key", settings.getProperty("SUPABASE_SECRET_KEY"))
        val configuration = SupabaseClientConfig()
        val reader = SupabaseJobStatusReader(configuration.supabaseClient(environment), ObjectMapper())
        val userId = UUID.randomUUID()
        val jobId = UUID.randomUUID()
        val resourceId = UUID.randomUUID()
        val database = Properties().apply {
            setProperty("user", settings.getProperty("DATABASE_USERNAME"))
            setProperty("password", settings.getProperty("DATABASE_PASSWORD"))
            setProperty("sslmode", "verify-full")
            setProperty("sslrootcert", settings.getProperty("SUPABASE_SSL_ROOT_CERT"))
            setProperty("currentSchema", "public,extensions")
            setProperty("connectTimeout", "10")
            setProperty("socketTimeout", "15")
        }
        try {
            DriverManager.getConnection(settings.getProperty("DATABASE_URL"), database).use { connection ->
                try {
                    connection.prepareStatement("INSERT INTO ai_interview_app.app_users(id,email) VALUES (?,?)").use {
                        it.setObject(1, userId)
                        it.setString(2, "supabase-smoke-$userId@example.invalid")
                        assertEquals(1, it.executeUpdate())
                    }
                    connection.prepareStatement("""
                        INSERT INTO ai_interview_app.background_jobs
                        (id,user_id,job_type,resource_type,resource_id,status,stage,request_payload,result_payload,enqueued_at)
                        VALUES (?,?,'ANALYSIS','resume',?,'SUCCEEDED','COMPLETED',
                        '{"resumeId":"malformed","prompt":"PRIVATE_SMOKE_PROMPT"}'::jsonb,
                        '{"score":84,"nested":{"items":[true,null]}}'::jsonb,now())
                    """.trimIndent()).use {
                        it.setObject(1, jobId)
                        it.setObject(2, userId)
                        it.setObject(3, resourceId)
                        assertEquals(1, it.executeUpdate())
                    }
                    val status = assertNotNull(reader.findForUser(jobId, userId))
                    assertEquals(JobStatus.SUCCEEDED, status.status)
                    assertEquals(resourceId, status.inputRefs.resumeId)
                    assertEquals(mapOf("score" to 84, "nested" to mapOf("items" to listOf(true, null))), status.result)
                    assertNull(status.error)
                    assertNotNull(status.createdAt)
                    assertNull(reader.findForUser(jobId, UUID.randomUUID()))
                    assertNull(reader.findForUser(UUID.randomUUID(), userId))
                    connection.prepareStatement("""
                        UPDATE ai_interview_app.background_jobs
                        SET status='FAILED',last_error='Smoke failure',error_code='SMOKE',retryable=false,completed_at=now()
                        WHERE id=? AND user_id=?
                    """.trimIndent()).use {
                        it.setObject(1, jobId)
                        it.setObject(2, userId)
                        assertEquals(1, it.executeUpdate())
                    }
                    val failed = assertNotNull(reader.findForUser(jobId, userId))
                    assertEquals(JobStatus.FAILED, failed.status)
                    assertEquals("SMOKE", failed.error?.code)
                    assertEquals(false, failed.error?.retryable)
                    assertNotNull(failed.completedAt)
                } finally {
                    connection.prepareStatement("DELETE FROM ai_interview_app.app_users WHERE id=?").use {
                        it.setObject(1, userId)
                        it.executeUpdate()
                    }
                }
            }
        } finally {
            configuration.destroy()
        }
    }
}
