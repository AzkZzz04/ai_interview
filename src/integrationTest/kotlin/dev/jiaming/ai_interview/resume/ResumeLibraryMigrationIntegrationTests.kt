package dev.jiaming.ai_interview.resume

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
class ResumeLibraryMigrationIntegrationTests {
    @Test
    fun v10BackfillsResumeLibraryFieldsAndCreatesDurableCleanupQueue() {
        migrateTo("9")
        val userId = UUID.randomUUID()
        val uploadId = UUID.randomUUID()
        val pasteId = UUID.randomUUID()
        connection().use { connection ->
            connection.prepareStatement("INSERT INTO ai_interview_app.app_users (id, email) VALUES (?, ?)").use {
                it.setObject(1, userId)
                it.setString(2, "resume-library-migration@ai-interview.dev")
                it.executeUpdate()
            }
            connection.prepareStatement("""
                INSERT INTO ai_interview_app.resumes (id, user_id, original_filename, storage_key, size_bytes, processing_status)
                VALUES (?, ?, 'Senior.Backend.pdf', 'resumes/legacy.pdf', 10, 'READY'),
                       (?, ?, 'pasted-resume.txt', NULL, 120, 'READY')
            """.trimIndent()).use {
                it.setObject(1, uploadId)
                it.setObject(2, userId)
                it.setObject(3, pasteId)
                it.setObject(4, userId)
                it.executeUpdate()
            }
        }

        migrateTo(null)
        connection().use { connection ->
            assertThat(value(connection, "SELECT name FROM ai_interview_app.resumes WHERE id = ?", uploadId)).isEqualTo("Senior.Backend")
            assertThat(value(connection, "SELECT source FROM ai_interview_app.resumes WHERE id = ?", uploadId)).isEqualTo("UPLOAD")
            assertThat(value(connection, "SELECT name FROM ai_interview_app.resumes WHERE id = ?", pasteId)).isEqualTo("pasted-resume")
            assertThat(value(connection, "SELECT source FROM ai_interview_app.resumes WHERE id = ?", pasteId)).isEqualTo("PASTE")
            assertThat(value(connection, "SELECT original_filename FROM ai_interview_app.resumes WHERE id = ?", pasteId)).isNull()
            assertThat(tableExists(connection, "storage_cleanup")).isTrue()
            assertThat(indexDefinition(connection, "uq_resumes_user_file_hash")).contains("WHERE (file_hash IS NOT NULL)")
        }
    }

    private fun migrateTo(target: String?) {
        val configuration = Flyway.configure().dataSource(POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password)
            .locations("classpath:db/migration")
        if (target != null) configuration.target(target)
        configuration.load().migrate()
    }

    private fun connection(): Connection = DriverManager.getConnection(POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password)

    private fun value(connection: Connection, sql: String, id: UUID): String? =
        connection.prepareStatement(sql).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { result ->
                assertThat(result.next()).isTrue()
                result.getString(1)
            }
        }

    private fun tableExists(connection: Connection, tableName: String): Boolean = connection.prepareStatement(
        "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'ai_interview_app' AND table_name = ?)"
    ).use { statement ->
        statement.setString(1, tableName)
        statement.executeQuery().use { result -> result.next(); result.getBoolean(1) }
    }

    private fun indexDefinition(connection: Connection, indexName: String): String = connection.prepareStatement(
        "SELECT indexdef FROM pg_indexes WHERE schemaname = 'ai_interview_app' AND indexname = ?"
    ).use { statement ->
        statement.setString(1, indexName)
        statement.executeQuery().use { result -> assertThat(result.next()).isTrue(); result.getString(1) }
    }

    companion object {
        @Container @JvmField val POSTGRES = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_interview_resume_library_migration_test").withUsername("ai_interview").withPassword("ai_interview")
    }
}
