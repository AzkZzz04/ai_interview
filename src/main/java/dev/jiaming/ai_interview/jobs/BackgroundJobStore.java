package dev.jiaming.ai_interview.jobs;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BackgroundJobStore {

	private static final String JOB_COLUMNS = """
		id, user_id, job_type, resource_type, resource_id, status, stage,
		request_payload::text AS request_payload, result_payload::text AS result_payload,
		request_fingerprint, attempts, max_attempts, error_code, last_error, retryable,
		run_after, created_at, updated_at, enqueued_at, started_at, completed_at,
		lease_token, lease_expires_at
		""";

	private final JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper;

	public BackgroundJobStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
	}

	@Transactional
	public BackgroundJob create(
		UUID userId,
		JobType jobType,
		String resourceType,
		UUID resourceId,
		JsonNode requestPayload,
		String requestFingerprint,
		int maxAttempts
	) {
		UUID jobId = UUID.randomUUID();
		jdbcTemplate.update(
			"""
				INSERT INTO ai_interview_app.background_jobs (
					id, user_id, job_type, resource_type, resource_id, status, stage,
					request_payload, request_fingerprint, max_attempts, run_after
				)
				VALUES (?, ?, ?, ?, ?, 'QUEUED', 'QUEUED', ?::jsonb, ?, ?, now())
				""",
			jobId,
			userId,
			jobType.name(),
			resourceType,
			resourceId,
			json(requestPayload),
			requestFingerprint,
			maxAttempts
		);
		return findById(jobId).orElseThrow();
	}

	public Optional<BackgroundJob> findReusable(
		UUID userId,
		JobType jobType,
		String requestFingerprint,
		Duration recentSuccessWindow
	) {
		Instant completedAfter = Instant.now().minus(recentSuccessWindow);
		List<BackgroundJob> jobs = jdbcTemplate.query(
			"""
				SELECT %s
				FROM ai_interview_app.background_jobs
				WHERE user_id = ?
				  AND job_type = ?
				  AND request_fingerprint = ?
				  AND (
					status IN ('QUEUED', 'PROCESSING', 'RETRYING')
					OR (status IN ('SUCCEEDED', 'PARTIAL') AND completed_at >= ?)
				  )
				ORDER BY
				  CASE WHEN status IN ('QUEUED', 'PROCESSING', 'RETRYING') THEN 0 ELSE 1 END,
				  created_at DESC
				LIMIT 1
				""".formatted(JOB_COLUMNS),
			this::mapJob,
			userId,
			jobType.name(),
			requestFingerprint,
			Timestamp.from(completedAfter)
		);
		return jobs.stream().findFirst();
	}

	public Optional<BackgroundJob> findForUser(UUID jobId, UUID userId) {
		return queryOne(
			"SELECT %s FROM ai_interview_app.background_jobs WHERE id = ? AND user_id = ?".formatted(JOB_COLUMNS),
			jobId,
			userId
		);
	}

	public Optional<BackgroundJob> findById(UUID jobId) {
		return queryOne(
			"SELECT %s FROM ai_interview_app.background_jobs WHERE id = ?".formatted(JOB_COLUMNS),
			jobId
		);
	}

	public List<UUID> findUndispatched(int limit) {
		return jdbcTemplate.query(
			"""
				SELECT id
				FROM ai_interview_app.background_jobs
				WHERE enqueued_at IS NULL
				  AND status IN ('QUEUED', 'RETRYING')
				  AND run_after <= now()
				ORDER BY created_at
				LIMIT ?
				""",
			(rs, rowNum) -> rs.getObject("id", UUID.class),
			limit
		);
	}

	public void markEnqueued(UUID jobId) {
		jdbcTemplate.update(
			"""
				UPDATE ai_interview_app.background_jobs
				SET enqueued_at = now(), updated_at = now()
				WHERE id = ? AND status IN ('QUEUED', 'RETRYING')
				""",
			jobId
		);
	}

	@Transactional
	public Optional<BackgroundJob> claim(UUID jobId, UUID leaseToken, Duration leaseDuration) {
		List<BackgroundJob> jobs = jdbcTemplate.query(
			"""
				UPDATE ai_interview_app.background_jobs
				SET status = 'PROCESSING',
					attempts = attempts + 1,
					started_at = COALESCE(started_at, now()),
					lease_token = ?,
					lease_expires_at = now() + (? * interval '1 second'),
					last_error = NULL,
					error_code = NULL,
					retryable = NULL,
					updated_at = now()
				WHERE id = ?
				  AND attempts < max_attempts
				  AND (
					(status IN ('QUEUED', 'RETRYING') AND run_after <= now())
					OR (status = 'PROCESSING' AND lease_expires_at < now())
				  )
				RETURNING %s
				""".formatted(JOB_COLUMNS),
			this::mapJob,
			leaseToken,
			leaseDuration.toSeconds(),
			jobId
		);
		return jobs.stream().findFirst();
	}

	public boolean extendLease(UUID jobId, UUID leaseToken, Duration leaseDuration) {
		return jdbcTemplate.update(
			"""
				UPDATE ai_interview_app.background_jobs
				SET lease_expires_at = now() + (? * interval '1 second'), updated_at = now()
				WHERE id = ? AND status = 'PROCESSING' AND lease_token = ?
				""",
			leaseDuration.toSeconds(),
			jobId,
			leaseToken
		) == 1;
	}

	public void updateStage(UUID jobId, UUID leaseToken, JobStage stage) {
		int updated = jdbcTemplate.update(
			"""
				UPDATE ai_interview_app.background_jobs
				SET stage = ?, updated_at = now()
				WHERE id = ? AND status = 'PROCESSING' AND lease_token = ?
				""",
			stage.name(),
			jobId,
			leaseToken
		);
		assertLeaseOwned(jobId, updated);
	}

	public void checkpointResult(UUID jobId, UUID leaseToken, JsonNode resultPayload) {
		int updated = jdbcTemplate.update(
			"""
				UPDATE ai_interview_app.background_jobs
				SET result_payload = ?::jsonb, updated_at = now()
				WHERE id = ? AND status = 'PROCESSING' AND lease_token = ?
				""",
			json(resultPayload),
			jobId,
			leaseToken
		);
		assertLeaseOwned(jobId, updated);
	}

	public boolean markSucceeded(UUID jobId, UUID leaseToken, JsonNode resultPayload) {
		return markTerminal(jobId, leaseToken, JobStatus.SUCCEEDED, resultPayload, null, null, false);
	}

	public boolean markFailed(UUID jobId, UUID leaseToken, String errorCode, String errorMessage, boolean partial) {
		return markTerminal(
			jobId,
			leaseToken,
			partial ? JobStatus.PARTIAL : JobStatus.FAILED,
			null,
			errorCode,
			errorMessage,
			false
		);
	}

	public boolean markRetrying(UUID jobId, UUID leaseToken, String errorCode, String errorMessage, Duration delay) {
		return jdbcTemplate.update(
			"""
				UPDATE ai_interview_app.background_jobs
				SET status = 'RETRYING',
					last_error = ?,
					error_code = ?,
					retryable = true,
					run_after = now() + (? * interval '1 second'),
					enqueued_at = NULL,
					lease_token = NULL,
					lease_expires_at = NULL,
					updated_at = now()
				WHERE id = ? AND status = 'PROCESSING' AND lease_token = ?
				""",
			truncate(errorMessage),
			errorCode,
			delay.toSeconds(),
			jobId,
			leaseToken
		) == 1;
	}

	public boolean releaseForRedispatch(UUID jobId, UUID leaseToken) {
		return jdbcTemplate.update(
			"""
				UPDATE ai_interview_app.background_jobs
				SET status = 'RETRYING',
					attempts = GREATEST(0, attempts - 1),
					run_after = now(),
					enqueued_at = NULL,
					lease_token = NULL,
					lease_expires_at = NULL,
					updated_at = now()
				WHERE id = ? AND status = 'PROCESSING' AND lease_token = ?
				""",
			jobId,
			leaseToken
		) == 1;
	}

	public boolean prepareDlqRecovery(UUID jobId) {
		return jdbcTemplate.update(
			"""
				UPDATE ai_interview_app.background_jobs
				SET status = CASE WHEN status = 'PROCESSING' THEN 'RETRYING' ELSE status END,
					run_after = now(),
					enqueued_at = NULL,
					lease_token = CASE WHEN status = 'PROCESSING' THEN NULL ELSE lease_token END,
					lease_expires_at = CASE WHEN status = 'PROCESSING' THEN NULL ELSE lease_expires_at END,
					updated_at = now()
				WHERE id = ?
				  AND attempts < max_attempts
				  AND (
					status IN ('QUEUED', 'RETRYING')
					OR (status = 'PROCESSING' AND lease_expires_at < now())
				  )
				""",
			jobId
		) == 1;
	}

	public boolean markExhaustedFromDlq(UUID jobId) {
		return jdbcTemplate.update(
			"""
				UPDATE ai_interview_app.background_jobs
				SET status = CASE
						WHEN job_type = 'ANALYSIS' AND jsonb_exists(result_payload, 'assessment') THEN 'PARTIAL'
						ELSE 'FAILED'
					END,
					last_error = 'SQS moved the job message to the dead-letter queue after retries were exhausted',
					error_code = 'RETRIES_EXHAUSTED_DLQ',
					retryable = false,
					completed_at = now(),
					lease_token = NULL,
					lease_expires_at = NULL,
					updated_at = now()
				WHERE id = ?
				  AND attempts >= max_attempts
				  AND status IN ('QUEUED', 'PROCESSING', 'RETRYING')
				  AND (status <> 'PROCESSING' OR lease_expires_at < now())
				""",
			jobId
		) == 1;
	}

	public int reapExpiredLeases() {
		return jdbcTemplate.update(
			"""
				UPDATE ai_interview_app.background_jobs
				SET status = CASE
						WHEN attempts >= max_attempts AND job_type = 'ANALYSIS'
							AND jsonb_exists(result_payload, 'assessment') THEN 'PARTIAL'
						WHEN attempts >= max_attempts THEN 'FAILED'
						ELSE 'RETRYING'
					END,
					last_error = 'Worker lease expired before the job completed',
					error_code = CASE
						WHEN attempts >= max_attempts THEN 'RETRIES_EXHAUSTED_WORKER_LEASE_EXPIRED'
						ELSE 'WORKER_LEASE_EXPIRED'
					END,
					retryable = CASE WHEN attempts >= max_attempts THEN false ELSE true END,
					run_after = now(),
					enqueued_at = CASE WHEN attempts >= max_attempts THEN enqueued_at ELSE NULL END,
					completed_at = CASE WHEN attempts >= max_attempts THEN now() ELSE completed_at END,
					lease_token = NULL,
					lease_expires_at = NULL,
					updated_at = now()
				WHERE status = 'PROCESSING' AND lease_expires_at < now()
				"""
		);
	}

	public int clearExpiredPayloads(int retentionDays) {
		return jdbcTemplate.update(
			"""
				UPDATE ai_interview_app.background_jobs
				SET request_payload = '{}'::jsonb, result_payload = NULL, updated_at = now()
				WHERE status IN ('SUCCEEDED', 'PARTIAL', 'FAILED')
				  AND completed_at < now() - (? * interval '1 day')
				  AND (request_payload <> '{}'::jsonb OR result_payload IS NOT NULL)
				""",
			retentionDays
		);
	}

	private boolean markTerminal(
		UUID jobId,
		UUID leaseToken,
		JobStatus status,
		JsonNode resultPayload,
		String errorCode,
		String errorMessage,
		boolean retryable
	) {
		return jdbcTemplate.update(
			"""
				UPDATE ai_interview_app.background_jobs
				SET status = ?,
					stage = CASE WHEN ? = 'SUCCEEDED' THEN 'COMPLETED' ELSE stage END,
					result_payload = COALESCE(?::jsonb, result_payload),
					last_error = ?,
					error_code = ?,
					retryable = ?,
					completed_at = now(),
					lease_token = NULL,
					lease_expires_at = NULL,
					updated_at = now()
				WHERE id = ? AND lease_token = ?
				""",
			status.name(),
			status.name(),
			resultPayload == null ? null : json(resultPayload),
			truncate(errorMessage),
			errorCode,
			retryable,
			jobId,
			leaseToken
		) == 1;
	}

	private void assertLeaseOwned(UUID jobId, int updatedRows) {
		if (updatedRows != 1) {
			throw new JobLeaseLostException(jobId);
		}
	}

	private Optional<BackgroundJob> queryOne(String sql, Object... arguments) {
		List<BackgroundJob> jobs = jdbcTemplate.query(sql, this::mapJob, arguments);
		return jobs.stream().findFirst();
	}

	private BackgroundJob mapJob(ResultSet rs, int rowNumber) throws SQLException {
		return new BackgroundJob(
			rs.getObject("id", UUID.class),
			rs.getObject("user_id", UUID.class),
			JobType.valueOf(rs.getString("job_type")),
			rs.getString("resource_type"),
			rs.getObject("resource_id", UUID.class),
			JobStatus.valueOf(rs.getString("status")),
			JobStage.valueOf(rs.getString("stage")),
			parseJson(rs.getString("request_payload")),
			parseJson(rs.getString("result_payload")),
			rs.getString("request_fingerprint"),
			rs.getInt("attempts"),
			rs.getInt("max_attempts"),
			rs.getString("error_code"),
			rs.getString("last_error"),
			(Boolean) rs.getObject("retryable"),
			instant(rs, "run_after"),
			instant(rs, "created_at"),
			instant(rs, "updated_at"),
			instant(rs, "enqueued_at"),
			instant(rs, "started_at"),
			instant(rs, "completed_at"),
			rs.getObject("lease_token", UUID.class),
			instant(rs, "lease_expires_at")
		);
	}

	private JsonNode parseJson(String value) throws SQLException {
		if (value == null) {
			return null;
		}
		try {
			return objectMapper.readTree(value);
		}
		catch (JsonProcessingException exception) {
			throw new SQLException("Could not parse background job JSON", exception);
		}
	}

	private String json(JsonNode value) {
		return value == null ? JsonNodeFactory.instance.objectNode().toString() : value.toString();
	}

	private Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp timestamp = rs.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant();
	}

	private String truncate(String value) {
		if (value == null || value.length() <= 4_000) {
			return value;
		}
		return value.substring(0, 4_000);
	}
}
