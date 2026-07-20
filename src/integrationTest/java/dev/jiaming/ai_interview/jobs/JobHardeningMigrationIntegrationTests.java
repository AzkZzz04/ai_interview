package dev.jiaming.ai_interview.jobs;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class JobHardeningMigrationIntegrationTests {

	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
		DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
	)
		.withDatabaseName("ai_interview_migration_test")
		.withUsername("ai_interview")
		.withPassword("ai_interview");

	@Test
	void upgradesLegacyJobsAndAddsIdempotentEffects() throws Exception {
		migrateTo("5");
		UUID userId = UUID.randomUUID();
		UUID pendingResumeId = UUID.randomUUID();
		UUID readyResumeId = UUID.randomUUID();
		UUID orphanResumeId = UUID.randomUUID();
		UUID validJobId = UUID.randomUUID();
		UUID legacyJobId = UUID.randomUUID();

		try (Connection connection = connection()) {
			insertUser(connection, userId);
			insertResume(connection, pendingResumeId, userId, null);
			insertResume(connection, readyResumeId, userId, "Java Spring Boot");
			insertResume(connection, orphanResumeId, userId, null);
			insertExtractionJob(connection, validJobId, userId, pendingResumeId);
			insertLegacyJob(connection, legacyJobId);
		}

		migrateTo(null);

		try (Connection connection = connection()) {
			assertThat(jobStatus(connection, validJobId)).isEqualTo("QUEUED");
			assertThat(jobStatus(connection, legacyJobId)).isEqualTo("FAILED");
			assertThat(errorCode(connection, legacyJobId)).isEqualTo("LEGACY_JOB_UNSUPPORTED");
			assertThat(resumeStatus(connection, pendingResumeId)).isEqualTo("PENDING");
			assertThat(resumeStatus(connection, readyResumeId)).isEqualTo("READY");
			assertThat(resumeStatus(connection, orphanResumeId)).isEqualTo("FAILED");
			assertThat(tableExists(connection, "background_job_effects")).isTrue();

			UUID resourceId = UUID.randomUUID();
			insertEffect(connection, validJobId, "ASSESSMENT", resourceId);
			assertThatThrownBy(() -> insertEffect(connection, validJobId, "ASSESSMENT", UUID.randomUUID()))
				.isInstanceOf(SQLException.class);
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

	private Connection connection() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	private void insertUser(Connection connection, UUID userId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
			"INSERT INTO ai_interview_app.app_users (id, email, display_name) VALUES (?, ?, ?)"
		)) {
			statement.setObject(1, userId);
			statement.setString(2, "migration-test@ai-interview.dev");
			statement.setString(3, "Migration Test");
			statement.executeUpdate();
		}
	}

	private void insertResume(Connection connection, UUID resumeId, UUID userId, String normalizedText) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			INSERT INTO ai_interview_app.resumes (
				id, user_id, original_filename, content_type, detected_content_type,
				size_bytes, storage_key, raw_text, normalized_text
			)
			VALUES (?, ?, ?, 'text/plain', 'text/plain', 10, ?, ?, ?)
			""")) {
			statement.setObject(1, resumeId);
			statement.setObject(2, userId);
			statement.setString(3, resumeId + ".txt");
			statement.setString(4, "resumes/" + resumeId);
			statement.setString(5, normalizedText);
			statement.setString(6, normalizedText);
			statement.executeUpdate();
		}
	}

	private void insertExtractionJob(Connection connection, UUID jobId, UUID userId, UUID resumeId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			INSERT INTO ai_interview_app.background_jobs (
				id, user_id, job_type, resource_type, resource_id, status, stage,
				request_payload, request_fingerprint, max_attempts, run_after
			)
			VALUES (?, ?, 'RESUME_EXTRACTION', 'resume', ?, 'QUEUED', 'QUEUED', ?::jsonb, ?, 3, now())
			""")) {
			statement.setObject(1, jobId);
			statement.setObject(2, userId);
			statement.setObject(3, resumeId);
			statement.setString(4, "{\"resumeId\":\"" + resumeId + "\",\"storageKey\":\"resumes/test\"}");
			statement.setString(5, "valid-migration-job");
			statement.executeUpdate();
		}
	}

	private void insertLegacyJob(Connection connection, UUID jobId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			INSERT INTO ai_interview_app.background_jobs (
				id, job_type, status, stage, request_payload, max_attempts, run_after
			)
			VALUES (?, 'LEGACY_INDEX', 'QUEUED', 'QUEUED', '{}'::jsonb, 3, now())
			""")) {
			statement.setObject(1, jobId);
			statement.executeUpdate();
		}
	}

	private String jobStatus(Connection connection, UUID jobId) throws SQLException {
		return value(connection, "SELECT status FROM ai_interview_app.background_jobs WHERE id = ?", jobId);
	}

	private String errorCode(Connection connection, UUID jobId) throws SQLException {
		return value(connection, "SELECT error_code FROM ai_interview_app.background_jobs WHERE id = ?", jobId);
	}

	private String resumeStatus(Connection connection, UUID resumeId) throws SQLException {
		return value(connection, "SELECT processing_status FROM ai_interview_app.resumes WHERE id = ?", resumeId);
	}

	private String value(Connection connection, String sql, UUID id) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setObject(1, id);
			try (ResultSet result = statement.executeQuery()) {
				assertThat(result.next()).isTrue();
				return result.getString(1);
			}
		}
	}

	private boolean tableExists(Connection connection, String tableName) throws SQLException {
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

	private void insertEffect(Connection connection, UUID jobId, String effectType, UUID resourceId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			INSERT INTO ai_interview_app.background_job_effects (job_id, effect_type, resource_id)
			VALUES (?, ?, ?)
			""")) {
			statement.setObject(1, jobId);
			statement.setString(2, effectType);
			statement.setObject(3, resourceId);
			statement.executeUpdate();
		}
	}
}
