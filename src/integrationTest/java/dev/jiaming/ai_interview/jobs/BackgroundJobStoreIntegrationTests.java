package dev.jiaming.ai_interview.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Exercises the real claim/lease/reap/DLQ SQL of {@link BackgroundJobStore} against Postgres.
 * {@code JobWorkerTests} covers the worker with a mocked store; this class validates the
 * conditional lease acquisition and fencing that a mock cannot.
 */
class BackgroundJobStoreIntegrationTests {

	private static final Duration LEASE = Duration.ofSeconds(300);

	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
		DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
	)
		.withDatabaseName("ai_interview_job_store_test")
		.withUsername("ai_interview")
		.withPassword("ai_interview");

	private static JdbcTemplate jdbcTemplate;

	private static BackgroundJobStore store;

	private static UUID userId;

	@BeforeAll
	static void setUp() {
		POSTGRES.start();
		DataSource dataSource = new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
		);
		jdbcTemplate = new JdbcTemplate(dataSource);
		Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
		store = new BackgroundJobStore(jdbcTemplate, new ObjectMapper());
		userId = UUID.randomUUID();
		jdbcTemplate.update(
			"INSERT INTO ai_interview_app.app_users (id, email) VALUES (?, ?)",
			userId, "job-store-test@ai-interview.dev"
		);
	}

	@AfterAll
	static void tearDown() {
		POSTGRES.stop();
	}

	@Test
	void claimAcquiresQueuedJobAndRejectsASecondHolder() {
		BackgroundJob job = createJob();
		UUID lease = UUID.randomUUID();

		Optional<BackgroundJob> claimed = store.claim(job.id(), lease, LEASE);

		assertThat(claimed).isPresent();
		assertThat(claimed.get().status()).isEqualTo(JobStatus.PROCESSING);
		assertThat(claimed.get().attempts()).isEqualTo(1);
		assertThat(claimed.get().leaseToken()).isEqualTo(lease);

		assertThat(store.claim(job.id(), UUID.randomUUID(), LEASE)).isEmpty();
	}

	@Test
	void claimTakesOverAnExpiredLease() {
		BackgroundJob job = createJob();
		store.claim(job.id(), UUID.randomUUID(), LEASE);
		expireLease(job.id());

		UUID takeover = UUID.randomUUID();
		Optional<BackgroundJob> claimed = store.claim(job.id(), takeover, LEASE);

		assertThat(claimed).isPresent();
		assertThat(claimed.get().leaseToken()).isEqualTo(takeover);
		assertThat(claimed.get().attempts()).isEqualTo(2);
	}

	@Test
	void extendLeaseOnlyForTheCurrentHolder() {
		BackgroundJob job = createJob();
		UUID lease = UUID.randomUUID();
		store.claim(job.id(), lease, LEASE);

		assertThat(store.extendLease(job.id(), lease, Duration.ofSeconds(600))).isTrue();
		assertThat(store.extendLease(job.id(), UUID.randomUUID(), Duration.ofSeconds(600))).isFalse();
	}

	@Test
	void stageAndCheckpointRequireLeaseOwnership() {
		BackgroundJob job = createJob();
		UUID lease = UUID.randomUUID();
		store.claim(job.id(), lease, LEASE);

		store.updateStage(job.id(), lease, JobStage.ASSESSING_RESUME);
		assertThat(store.findById(job.id()).orElseThrow().stage()).isEqualTo(JobStage.ASSESSING_RESUME);

		UUID wrongLease = UUID.randomUUID();
		assertThatThrownBy(() -> store.updateStage(job.id(), wrongLease, JobStage.GENERATING_QUESTIONS))
			.isInstanceOf(JobLeaseLostException.class);
		assertThatThrownBy(() -> store.checkpointResult(job.id(), wrongLease, assessmentResult()))
			.isInstanceOf(JobLeaseLostException.class);
	}

	@Test
	void markSucceededClearsLeaseAndStoresResult() {
		BackgroundJob job = createJob();
		UUID lease = UUID.randomUUID();
		store.claim(job.id(), lease, LEASE);

		assertThat(store.markSucceeded(job.id(), lease, assessmentResult())).isTrue();

		BackgroundJob done = store.findById(job.id()).orElseThrow();
		assertThat(done.status()).isEqualTo(JobStatus.SUCCEEDED);
		assertThat(done.stage()).isEqualTo(JobStage.COMPLETED);
		assertThat(done.leaseToken()).isNull();
		assertThat(done.completedAt()).isNotNull();
		assertThat(done.resultPayload().hasNonNull("assessment")).isTrue();

		assertThat(store.markSucceeded(job.id(), lease, assessmentResult())).isFalse();
	}

	@Test
	void markFailedRecordsPartialWhenAllowed() {
		BackgroundJob terminal = createJob();
		UUID leaseA = UUID.randomUUID();
		store.claim(terminal.id(), leaseA, LEASE);
		assertThat(store.markFailed(terminal.id(), leaseA, "PROCESSING_ERROR", "boom", false)).isTrue();
		BackgroundJob failed = store.findById(terminal.id()).orElseThrow();
		assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
		assertThat(failed.errorCode()).isEqualTo("PROCESSING_ERROR");

		BackgroundJob partial = createJob();
		UUID leaseB = UUID.randomUUID();
		store.claim(partial.id(), leaseB, LEASE);
		assertThat(store.markFailed(partial.id(), leaseB, "PARTIAL_CODE", "half", true)).isTrue();
		assertThat(store.findById(partial.id()).orElseThrow().status()).isEqualTo(JobStatus.PARTIAL);
	}

	@Test
	void markRetryingReschedulesAndClearsLease() {
		BackgroundJob job = createJob();
		UUID lease = UUID.randomUUID();
		store.claim(job.id(), lease, LEASE);

		assertThat(store.markRetrying(job.id(), lease, "RETRY_CODE", "later", Duration.ofSeconds(30))).isTrue();

		BackgroundJob retrying = store.findById(job.id()).orElseThrow();
		assertThat(retrying.status()).isEqualTo(JobStatus.RETRYING);
		assertThat(retrying.leaseToken()).isNull();
		assertThat(retrying.enqueuedAt()).isNull();
		assertThat(retrying.retryable()).isTrue();
		assertThat(retrying.runAfter()).isAfter(retrying.createdAt());

		assertThat(store.markRetrying(job.id(), UUID.randomUUID(), "X", "y", Duration.ofSeconds(1))).isFalse();
	}

	@Test
	void reapRequeuesExpiredLeaseWithAttemptsRemaining() {
		BackgroundJob job = createJob();
		store.claim(job.id(), UUID.randomUUID(), LEASE);
		expireLease(job.id());

		assertThat(store.reapExpiredLeases()).isGreaterThanOrEqualTo(1);

		BackgroundJob reaped = store.findById(job.id()).orElseThrow();
		assertThat(reaped.status()).isEqualTo(JobStatus.RETRYING);
		assertThat(reaped.errorCode()).isEqualTo("WORKER_LEASE_EXPIRED");
		assertThat(reaped.leaseToken()).isNull();
	}

	@Test
	void reapFailsExpiredLeaseWhenAttemptsAreExhausted() {
		BackgroundJob job = createJob();
		store.claim(job.id(), UUID.randomUUID(), LEASE);
		jdbcTemplate.update(
			"UPDATE ai_interview_app.background_jobs "
				+ "SET attempts = max_attempts, lease_expires_at = now() - interval '1 minute' WHERE id = ?",
			job.id()
		);

		store.reapExpiredLeases();

		BackgroundJob reaped = store.findById(job.id()).orElseThrow();
		assertThat(reaped.status()).isEqualTo(JobStatus.FAILED);
		assertThat(reaped.errorCode()).isEqualTo("RETRIES_EXHAUSTED_WORKER_LEASE_EXPIRED");
	}

	@Test
	void undispatchedJobsBecomeInvisibleOnceEnqueued() {
		BackgroundJob job = createJob();

		assertThat(store.findUndispatched(50)).contains(job.id());
		store.markEnqueued(job.id());
		assertThat(store.findUndispatched(50)).doesNotContain(job.id());
	}

	@Test
	void markExhaustedFromDlqFailsAJobPastItsAttemptLimit() {
		BackgroundJob job = createJob();
		jdbcTemplate.update(
			"UPDATE ai_interview_app.background_jobs SET attempts = max_attempts WHERE id = ?",
			job.id()
		);

		assertThat(store.markExhaustedFromDlq(job.id())).isTrue();

		BackgroundJob failed = store.findById(job.id()).orElseThrow();
		assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
		assertThat(failed.errorCode()).isEqualTo("RETRIES_EXHAUSTED_DLQ");
	}

	private BackgroundJob createJob() {
		ObjectNode payload = new ObjectMapper().createObjectNode().put("resumeText", "Java");
		return store.createIfAbsent(
			userId, JobType.ANALYSIS, "resume", null, payload, UUID.randomUUID().toString(), 3
		).orElseThrow();
	}

	private void expireLease(UUID jobId) {
		jdbcTemplate.update(
			"UPDATE ai_interview_app.background_jobs SET lease_expires_at = now() - interval '1 minute' WHERE id = ?",
			jobId
		);
	}

	private ObjectNode assessmentResult() {
		ObjectNode result = new ObjectMapper().createObjectNode();
		result.putObject("assessment").put("overallScore", 80);
		return result;
	}
}
