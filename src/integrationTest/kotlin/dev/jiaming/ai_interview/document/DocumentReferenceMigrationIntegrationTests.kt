package dev.jiaming.ai_interview.document

import dev.jiaming.ai_interview.common.ContentHasher
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

@Testcontainers
class DocumentReferenceMigrationIntegrationTests {
    @Test
    fun backfillsExactRuntimeHashAndCreatesRagRegistry() {
        migrateTo("6")
        val userId = UUID.randomUUID(); val resumeId = UUID.randomUUID(); val jobDescriptionId = UUID.randomUUID()
        val resumeText = "EXPERIENCE\nJava  Spring Boot\n数据"; val jobDescriptionText = "Build APIs\nPostgreSQL"
        connection().use { connection ->
            connection.prepareStatement("INSERT INTO ai_interview_app.app_users (id, email) VALUES (?, ?)").use { user -> user.setObject(1, userId); user.setString(2, "document-migration@ai-interview.dev"); user.executeUpdate() }
            connection.prepareStatement("""INSERT INTO ai_interview_app.resumes (id, user_id, original_filename, size_bytes, normalized_text, processing_status) VALUES (?, ?, 'resume.txt', 10, ?, 'READY')""").use { resume -> resume.setObject(1, resumeId); resume.setObject(2, userId); resume.setString(3, resumeText); resume.executeUpdate() }
            connection.prepareStatement("""INSERT INTO ai_interview_app.job_descriptions (id, user_id, raw_text, normalized_text) VALUES (?, ?, ?, ?)""").use { jd -> jd.setObject(1, jobDescriptionId); jd.setObject(2, userId); jd.setString(3, jobDescriptionText); jd.setString(4, jobDescriptionText); jd.executeUpdate() }
        }
        migrateTo(null)
        val hasher = ContentHasher()
        connection().use { connection ->
            assertThat(value(connection, "SELECT content_hash FROM ai_interview_app.resumes WHERE id = ?", resumeId)).isEqualTo(hasher.sha256(resumeText))
            assertThat(value(connection, "SELECT content_hash FROM ai_interview_app.job_descriptions WHERE id = ?", jobDescriptionId)).isEqualTo(hasher.sha256(jobDescriptionText))
            assertThat(tableExists(connection, "rag_document_indexes")).isTrue()
        }
    }

    private fun migrateTo(target: String?) {
        val configuration = Flyway.configure().dataSource(POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password).locations("classpath:db/migration")
        if (target != null) configuration.target(target)
        configuration.load().migrate()
    }
    private fun connection(): Connection = DriverManager.getConnection(POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password)
    private fun value(connection: Connection, sql: String, id: UUID): String = connection.prepareStatement(sql).use { statement -> statement.setObject(1, id); statement.executeQuery().use { result -> assertThat(result.next()).isTrue(); result.getString(1) } }
    private fun tableExists(connection: Connection, tableName: String): Boolean = connection.prepareStatement("""SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'ai_interview_app' AND table_name = ?)""").use { statement -> statement.setString(1, tableName); statement.executeQuery().use { result -> result.next(); result.getBoolean(1) } }

    companion object {
        @Container @JvmField val POSTGRES = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_interview_document_migration_test").withUsername("ai_interview").withPassword("ai_interview")
    }
}
