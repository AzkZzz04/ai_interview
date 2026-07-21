package dev.jiaming.ai_interview.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import dev.jiaming.ai_interview.common.ContentHasher;

@Testcontainers
class DocumentReferenceMigrationIntegrationTests {

	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
		DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
	)
		.withDatabaseName("ai_interview_document_migration_test")
		.withUsername("ai_interview")
		.withPassword("ai_interview");

	@Test
	void backfillsExactRuntimeHashAndCreatesRagRegistry() throws Exception {
		migrateTo("6");
		UUID userId = UUID.randomUUID();
		UUID resumeId = UUID.randomUUID();
		UUID jobDescriptionId = UUID.randomUUID();
		String resumeText = "EXPERIENCE\nJava  Spring Boot\n\u6570\u636e";
		String jobDescriptionText = "Build APIs\nPostgreSQL";

		try (Connection connection = connection()) {
			try (PreparedStatement user = connection.prepareStatement(
				"INSERT INTO ai_interview_app.app_users (id, email) VALUES (?, ?)"
			)) {
				user.setObject(1, userId);
				user.setString(2, "document-migration@ai-interview.dev");
				user.executeUpdate();
			}
			try (PreparedStatement resume = connection.prepareStatement("""
				INSERT INTO ai_interview_app.resumes (
					id, user_id, original_filename, size_bytes, normalized_text, processing_status
				) VALUES (?, ?, 'resume.txt', 10, ?, 'READY')
				""")) {
				resume.setObject(1, resumeId);
				resume.setObject(2, userId);
				resume.setString(3, resumeText);
				resume.executeUpdate();
			}
			try (PreparedStatement jobDescription = connection.prepareStatement("""
				INSERT INTO ai_interview_app.job_descriptions (
					id, user_id, raw_text, normalized_text
				) VALUES (?, ?, ?, ?)
				""")) {
				jobDescription.setObject(1, jobDescriptionId);
				jobDescription.setObject(2, userId);
				jobDescription.setString(3, jobDescriptionText);
				jobDescription.setString(4, jobDescriptionText);
				jobDescription.executeUpdate();
			}
		}

		migrateTo(null);

		ContentHasher hasher = new ContentHasher();
		try (Connection connection = connection()) {
			assertThat(value(connection,
				"SELECT content_hash FROM ai_interview_app.resumes WHERE id = ?", resumeId))
				.isEqualTo(hasher.sha256(resumeText));
			assertThat(value(connection,
				"SELECT content_hash FROM ai_interview_app.job_descriptions WHERE id = ?", jobDescriptionId))
				.isEqualTo(hasher.sha256(jobDescriptionText));
			assertThat(tableExists(connection, "rag_document_indexes")).isTrue();
		}
	}

	private void migrateTo(String target) {
		var configuration = Flyway.configure()
			.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration");
		if (target != null) {
			configuration.target(target);
		}
		configuration.load().migrate();
	}

	private Connection connection() throws Exception {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	private String value(Connection connection, String sql, UUID id) throws Exception {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setObject(1, id);
			try (ResultSet result = statement.executeQuery()) {
				assertThat(result.next()).isTrue();
				return result.getString(1);
			}
		}
	}

	private boolean tableExists(Connection connection, String tableName) throws Exception {
		try (PreparedStatement statement = connection.prepareStatement("""
			SELECT EXISTS (
				SELECT 1 FROM information_schema.tables
				WHERE table_schema = 'ai_interview_app' AND table_name = ?
			)
			""")) {
			statement.setString(1, tableName);
			try (ResultSet result = statement.executeQuery()) {
				result.next();
				return result.getBoolean(1);
			}
		}
	}
}
