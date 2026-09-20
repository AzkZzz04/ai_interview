package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.UUID

class BackgroundJobStoreIntegrationTests {
    @Test fun claimAcquiresQueuedJobAndRejectsASecondHolder() { val job = createJob(); val lease = UUID.randomUUID(); val claimed = store.claim(job.id(), lease, LEASE); assertThat(claimed).isPresent(); assertThat(claimed.get().status()).isEqualTo(JobStatus.PROCESSING); assertThat(claimed.get().attempts()).isEqualTo(1); assertThat(claimed.get().leaseToken()).isEqualTo(lease); assertThat(store.claim(job.id(), UUID.randomUUID(), LEASE)).isEmpty() }
    @Test fun claimTakesOverAnExpiredLease() { val job = createJob(); store.claim(job.id(), UUID.randomUUID(), LEASE); expireLease(job.id()); val takeover = UUID.randomUUID(); val claimed = store.claim(job.id(), takeover, LEASE); assertThat(claimed).isPresent(); assertThat(claimed.get().leaseToken()).isEqualTo(takeover); assertThat(claimed.get().attempts()).isEqualTo(2) }
    @Test fun extendLeaseOnlyForTheCurrentHolder() { val job = createJob(); val lease = UUID.randomUUID(); store.claim(job.id(), lease, LEASE); assertThat(store.extendLease(job.id(), lease, Duration.ofSeconds(600))).isTrue(); assertThat(store.extendLease(job.id(), UUID.randomUUID(), Duration.ofSeconds(600))).isFalse() }
    @Test fun stageAndCheckpointRequireLeaseOwnership() { val job = createJob(); val lease = UUID.randomUUID(); store.claim(job.id(), lease, LEASE); store.updateStage(job.id(), lease, JobStage.ASSESSING_RESUME); assertThat(store.findById(job.id()).orElseThrow().stage()).isEqualTo(JobStage.ASSESSING_RESUME); val wrong = UUID.randomUUID(); assertThatThrownBy { store.updateStage(job.id(), wrong, JobStage.GENERATING_QUESTIONS) }.isInstanceOf(JobLeaseLostException::class.java); assertThatThrownBy { store.checkpointResult(job.id(), wrong, assessmentResult()) }.isInstanceOf(JobLeaseLostException::class.java) }
    @Test fun markSucceededClearsLeaseAndStoresResult() { val job = createJob(); val lease = UUID.randomUUID(); store.claim(job.id(), lease, LEASE); assertThat(store.markSucceeded(job.id(), lease, assessmentResult())).isTrue(); val done = store.findById(job.id()).orElseThrow(); assertThat(done.status()).isEqualTo(JobStatus.SUCCEEDED); assertThat(done.stage()).isEqualTo(JobStage.COMPLETED); assertThat(done.leaseToken()).isNull(); assertThat(done.completedAt()).isNotNull(); assertThat(done.resultPayload().hasNonNull("assessment")).isTrue(); assertThat(store.markSucceeded(job.id(), lease, assessmentResult())).isFalse() }
    @Test fun markFailedRecordsPartialWhenAllowed() { val terminal = createJob(); val leaseA = UUID.randomUUID(); store.claim(terminal.id(), leaseA, LEASE); assertThat(store.markFailed(terminal.id(), leaseA, "PROCESSING_ERROR", "boom", false)).isTrue(); val failed = store.findById(terminal.id()).orElseThrow(); assertThat(failed.status()).isEqualTo(JobStatus.FAILED); assertThat(failed.errorCode()).isEqualTo("PROCESSING_ERROR"); val partial = createJob(); val leaseB = UUID.randomUUID(); store.claim(partial.id(), leaseB, LEASE); assertThat(store.markFailed(partial.id(), leaseB, "PARTIAL_CODE", "half", true)).isTrue(); assertThat(store.findById(partial.id()).orElseThrow().status()).isEqualTo(JobStatus.PARTIAL) }
    @Test fun markRetryingReschedulesAndClearsLease() { val job = createJob(); val lease = UUID.randomUUID(); store.claim(job.id(), lease, LEASE); assertThat(store.markRetrying(job.id(), lease, "RETRY_CODE", "later", Duration.ofSeconds(30))).isTrue(); val retrying = store.findById(job.id()).orElseThrow(); assertThat(retrying.status()).isEqualTo(JobStatus.RETRYING); assertThat(retrying.leaseToken()).isNull(); assertThat(retrying.enqueuedAt()).isNull(); assertThat(retrying.retryable()).isTrue(); assertThat(retrying.runAfter()).isAfter(retrying.createdAt()); assertThat(store.markRetrying(job.id(), UUID.randomUUID(), "X", "y", Duration.ofSeconds(1))).isFalse() }
    @Test fun reapRequeuesExpiredLeaseWithAttemptsRemaining() { val job = createJob(); store.claim(job.id(), UUID.randomUUID(), LEASE); expireLease(job.id()); assertThat(store.reapExpiredLeases()).isGreaterThanOrEqualTo(1); val reaped = store.findById(job.id()).orElseThrow(); assertThat(reaped.status()).isEqualTo(JobStatus.RETRYING); assertThat(reaped.errorCode()).isEqualTo("WORKER_LEASE_EXPIRED"); assertThat(reaped.leaseToken()).isNull() }
    @Test fun reapFailsExpiredLeaseWhenAttemptsAreExhausted() { val job = createJob(); store.claim(job.id(), UUID.randomUUID(), LEASE); jdbcTemplate.update("UPDATE ai_interview_app.background_jobs SET attempts = max_attempts, lease_expires_at = now() - interval '1 minute' WHERE id = ?", job.id()); store.reapExpiredLeases(); val reaped = store.findById(job.id()).orElseThrow(); assertThat(reaped.status()).isEqualTo(JobStatus.FAILED); assertThat(reaped.errorCode()).isEqualTo("RETRIES_EXHAUSTED_WORKER_LEASE_EXPIRED") }
    @Test fun undispatchedJobsBecomeInvisibleOnceEnqueued() { val job = createJob(); assertThat(store.findUndispatched(50)).contains(job.id()); store.markEnqueued(job.id()); assertThat(store.findUndispatched(50)).doesNotContain(job.id()) }
    @Test fun markExhaustedFromDlqFailsAJobPastItsAttemptLimit() { val job = createJob(); jdbcTemplate.update("UPDATE ai_interview_app.background_jobs SET attempts = max_attempts WHERE id = ?", job.id()); assertThat(store.markExhaustedFromDlq(job.id())).isTrue(); val failed = store.findById(job.id()).orElseThrow(); assertThat(failed.status()).isEqualTo(JobStatus.FAILED); assertThat(failed.errorCode()).isEqualTo("RETRIES_EXHAUSTED_DLQ") }
    private fun createJob(): BackgroundJob { val payload = ObjectMapper().createObjectNode().put("resumeText", "Java"); return store.createIfAbsent(userId, JobType.ANALYSIS, "resume", null, payload, UUID.randomUUID().toString(), 3).orElseThrow() }
    private fun expireLease(id: UUID) { jdbcTemplate.update("UPDATE ai_interview_app.background_jobs SET lease_expires_at = now() - interval '1 minute' WHERE id = ?", id) }
    private fun assessmentResult(): ObjectNode = ObjectMapper().createObjectNode().also { it.putObject("assessment").put("overallScore", 80) }
    companion object {
        private val LEASE = Duration.ofSeconds(300); private val POSTGRES = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")).withDatabaseName("ai_interview_job_store_test").withUsername("ai_interview").withPassword("ai_interview")
        private lateinit var jdbcTemplate: JdbcTemplate; private lateinit var store: BackgroundJobStore; private lateinit var userId: UUID
        @BeforeAll @JvmStatic fun setUp() { POSTGRES.start(); val source = DriverManagerDataSource(POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password); jdbcTemplate = JdbcTemplate(source); Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate(); store = BackgroundJobStore(jdbcTemplate, ObjectMapper()); userId = UUID.randomUUID(); jdbcTemplate.update("INSERT INTO ai_interview_app.app_users (id, email) VALUES (?, ?)", userId, "job-store-test@ai-interview.dev") }
        @AfterAll @JvmStatic fun tearDown() { POSTGRES.stop() }
    }
}
