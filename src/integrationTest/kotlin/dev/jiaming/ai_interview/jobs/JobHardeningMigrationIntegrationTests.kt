package dev.jiaming.ai_interview.jobs

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

@Testcontainers
class JobHardeningMigrationIntegrationTests {
    @Test fun upgradesLegacyJobsAndAddsIdempotentEffects() {
        migrateTo("5")
        val userId = UUID.randomUUID(); val pendingId = UUID.randomUUID(); val readyId = UUID.randomUUID(); val orphanId = UUID.randomUUID(); val validJobId = UUID.randomUUID(); val legacyJobId = UUID.randomUUID()
        connection().use { c -> insertUser(c, userId); insertResume(c, pendingId, userId, null); insertResume(c, readyId, userId, "Java Spring Boot"); insertResume(c, orphanId, userId, null); insertExtractionJob(c, validJobId, userId, pendingId); insertLegacyJob(c, legacyJobId) }
        migrateTo(null)
        connection().use { c ->
            assertThat(jobStatus(c, validJobId)).isEqualTo("QUEUED"); assertThat(jobStatus(c, legacyJobId)).isEqualTo("FAILED"); assertThat(errorCode(c, legacyJobId)).isEqualTo("LEGACY_JOB_UNSUPPORTED"); assertThat(resumeStatus(c, pendingId)).isEqualTo("PENDING"); assertThat(resumeStatus(c, readyId)).isEqualTo("READY"); assertThat(resumeStatus(c, orphanId)).isEqualTo("FAILED"); assertThat(tableExists(c, "background_job_effects")).isTrue()
            insertEffect(c, validJobId, "ASSESSMENT", UUID.randomUUID()); assertThatThrownBy { insertEffect(c, validJobId, "ASSESSMENT", UUID.randomUUID()) }.isInstanceOf(SQLException::class.java)
        }
    }
    private fun migrateTo(target: String?) { val config = Flyway.configure().dataSource(POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password).locations("classpath:db/migration"); if (target != null) config.target(target); config.load().migrate() }
    private fun connection(): Connection = DriverManager.getConnection(POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password)
    private fun insertUser(c: Connection, id: UUID) { c.prepareStatement("INSERT INTO ai_interview_app.app_users (id, email, display_name) VALUES (?, ?, ?)").use { s -> s.setObject(1, id); s.setString(2, "migration-test@ai-interview.dev"); s.setString(3, "Migration Test"); s.executeUpdate() } }
    private fun insertResume(c: Connection, id: UUID, user: UUID, text: String?) { c.prepareStatement("""INSERT INTO ai_interview_app.resumes (id, user_id, original_filename, content_type, detected_content_type, size_bytes, storage_key, raw_text, normalized_text) VALUES (?, ?, ?, 'text/plain', 'text/plain', 10, ?, ?, ?)""").use { s -> s.setObject(1, id); s.setObject(2, user); s.setString(3, "$id.txt"); s.setString(4, "resumes/$id"); s.setString(5, text); s.setString(6, text); s.executeUpdate() } }
    private fun insertExtractionJob(c: Connection, job: UUID, user: UUID, resume: UUID) { c.prepareStatement("""INSERT INTO ai_interview_app.background_jobs (id, user_id, job_type, resource_type, resource_id, status, stage, request_payload, request_fingerprint, max_attempts, run_after) VALUES (?, ?, 'RESUME_EXTRACTION', 'resume', ?, 'QUEUED', 'QUEUED', ?::jsonb, ?, 3, now())""").use { s -> s.setObject(1, job); s.setObject(2, user); s.setObject(3, resume); s.setString(4, "{\"resumeId\":\"$resume\",\"storageKey\":\"resumes/test\"}"); s.setString(5, "valid-migration-job"); s.executeUpdate() } }
    private fun insertLegacyJob(c: Connection, id: UUID) { c.prepareStatement("""INSERT INTO ai_interview_app.background_jobs (id, job_type, status, stage, request_payload, max_attempts, run_after) VALUES (?, 'LEGACY_INDEX', 'QUEUED', 'QUEUED', '{}'::jsonb, 3, now())""").use { it.setObject(1, id); it.executeUpdate() } }
    private fun jobStatus(c: Connection, id: UUID) = value(c, "SELECT status FROM ai_interview_app.background_jobs WHERE id = ?", id)
    private fun errorCode(c: Connection, id: UUID) = value(c, "SELECT error_code FROM ai_interview_app.background_jobs WHERE id = ?", id)
    private fun resumeStatus(c: Connection, id: UUID) = value(c, "SELECT processing_status FROM ai_interview_app.resumes WHERE id = ?", id)
    private fun value(c: Connection, sql: String, id: UUID): String = c.prepareStatement(sql).use { s -> s.setObject(1, id); s.executeQuery().use { r -> assertThat(r.next()).isTrue(); r.getString(1) } }
    private fun tableExists(c: Connection, name: String): Boolean = c.prepareStatement("""SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'ai_interview_app' AND table_name = ?)""").use { s -> s.setString(1, name); s.executeQuery().use { r -> r.next(); r.getBoolean(1) } }
    private fun insertEffect(c: Connection, job: UUID, type: String, resource: UUID) { c.prepareStatement("INSERT INTO ai_interview_app.background_job_effects (job_id, effect_type, resource_id) VALUES (?, ?, ?)").use { s -> s.setObject(1, job); s.setString(2, type); s.setObject(3, resource); s.executeUpdate() } }
    companion object { @Container @JvmField val POSTGRES = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")).withDatabaseName("ai_interview_migration_test").withUsername("ai_interview").withPassword("ai_interview") }
}
