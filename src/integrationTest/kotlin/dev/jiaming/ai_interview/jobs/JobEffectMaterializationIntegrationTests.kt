package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.coach.AssessmentScores
import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.score.ResumeScoreResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.*
import org.springframework.context.annotation.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.*
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class JobEffectMaterializationIntegrationTests {
    @Test @Order(1) fun migrationQuarantinesLegacyJobsAndClassifiesExistingResumes() {
        assertThat(resumeStatus(migratedReadyResumeId)).isEqualTo("READY"); assertThat(resumeStatus(migratedPendingResumeId)).isEqualTo("PENDING"); assertThat(resumeStatus(migratedFailedResumeId)).isEqualTo("FAILED")
        assertThat(jdbcTemplate.queryForObject("SELECT failure_code FROM ai_interview_app.resumes WHERE id = ?", String::class.java, migratedFailedResumeId)).isEqualTo("LEGACY_RESUME_UNRESOLVED")
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM ai_interview_app.background_jobs WHERE id = ?", String::class.java, migratedLegacyJobId)).isEqualTo("FAILED")
        assertThat(jdbcTemplate.queryForObject("SELECT error_code FROM ai_interview_app.background_jobs WHERE id = ?", String::class.java, migratedLegacyJobId)).isEqualTo("LEGACY_JOB_UNSUPPORTED")
        assertThat(jdbcTemplate.queryForObject("SELECT to_regclass('ai_interview_app.background_job_effects') IS NOT NULL", Boolean::class.java)).isTrue()
    }
    @Test @Order(2) fun repeatedMaterializationReusesOneEffect() {
        resetDomainTables(); val user = localUserService.localUserId(); val lease = UUID.randomUUID(); val resume = insertResume(user); val job = createProcessingJob(user, JobType.RESUME_SCORE, lease)
        val first = materializationService.materializeResumeScore(job, lease, resume, score()); val second = materializationService.materializeResumeScore(job, lease, resume, score())
        assertThat(second).isEqualTo(first); assertThat(count("resume_scores")).isEqualTo(1); assertThat(count("background_job_effects")).isEqualTo(1)
        assertThat(jdbcTemplate.queryForObject("SELECT resume_id FROM ai_interview_app.resume_scores WHERE id = ?", UUID::class.java, first)).isEqualTo(resume)
    }
    @Test @Order(3) fun concurrentMaterializationCommitsOneEffect() {
        resetDomainTables(); val user = localUserService.localUserId(); val lease = UUID.randomUUID(); val resume = insertResume(user); val job = createProcessingJob(user, JobType.RESUME_SCORE, lease); val start = CountDownLatch(1); val executor = Executors.newFixedThreadPool(2)
        try { val first = executor.submit<UUID> { start.await(); materializationService.materializeResumeScore(job, lease, resume, score()) }; val second = executor.submit<UUID> { start.await(); materializationService.materializeResumeScore(job, lease, resume, score()) }; start.countDown(); assertThat(first.get()).isEqualTo(second.get()); assertThat(count("resume_scores")).isEqualTo(1); assertThat(count("background_job_effects")).isEqualTo(1) } finally { executor.shutdownNow() }
    }
    @Test @Order(4) fun expiredOrReplacedLeaseCannotMaterializeAnEffect() {
        resetDomainTables(); val user = localUserService.localUserId(); val storedLease = UUID.randomUUID(); val resume = insertResume(user); val job = createProcessingJob(user, JobType.RESUME_SCORE, storedLease)
        assertThatThrownBy { materializationService.materializeResumeScore(job, UUID.randomUUID(), resume, score()) }.isInstanceOf(JobLeaseLostException::class.java)
        jdbcTemplate.update("UPDATE ai_interview_app.background_jobs SET lease_expires_at = now() - interval '1 second' WHERE id = ?", job.id); assertThatThrownBy { materializationService.materializeResumeScore(job, storedLease, resume, score()) }.isInstanceOf(JobLeaseLostException::class.java); assertThat(count("background_job_effects")).isZero(); assertThat(count("resume_scores")).isZero()
    }
    @Test @Order(6) fun payloadCleanupRetainsOnlyStableInputReferences() {
        resetDomainTables(); val user = localUserService.localUserId(); val resume = UUID.randomUUID(); val targetJob = UUID.randomUUID(); val jobId = UUID.randomUUID()
        jdbcTemplate.update("""INSERT INTO ai_interview_app.background_jobs (id, user_id, job_type, resource_type, resource_id, status, stage, request_payload, result_payload, max_attempts, completed_at) VALUES (?, ?, 'JOB_FIT', 'job-fit', ?, 'SUCCEEDED', 'COMPLETED', jsonb_build_object('payloadVersion', 1, 'resumeId', ?::text, 'targetJobId', ?::text, 'resumeText', 'PII_RESUME_MARKER', 'jobDescription', 'PII_JOB_MARKER'), jsonb_build_object('fitScore', 90), 3, now() - interval '8 days')""", jobId, user, UUID.randomUUID(), resume, targetJob)
        val practiceSet = UUID.randomUUID(); val attempt = UUID.randomUUID(); val attemptJobId = UUID.randomUUID()
        jdbcTemplate.update("""INSERT INTO ai_interview_app.background_jobs (id, user_id, job_type, resource_type, resource_id, status, stage, request_payload, max_attempts, completed_at) VALUES (?, ?, 'ANSWER_FEEDBACK', 'attempt', ?, 'FAILED', 'SCORING_ANSWER', jsonb_build_object('payloadVersion', 3, 'targetJobId', ?::text, 'practiceSetId', ?::text, 'attemptId', ?::text, 'answerText', 'PII_ANSWER_MARKER'), 3, now() - interval '8 days')""", attemptJobId, user, attempt, targetJob, practiceSet, attempt)
        val store = BackgroundJobStore(jdbcTemplate, objectMapper()); assertThat(store.clearExpiredPayloads(7)).isEqualTo(2); val cleaned = store.findById(jobId).orElseThrow(); val payload = requireNotNull(cleaned.requestPayload); assertThat(JobInputRefs.from(cleaned)).isEqualTo(JobInputRefs(resume, targetJob, null, null)); assertThat(payload.toString()).doesNotContain("PII_RESUME_MARKER", "PII_JOB_MARKER"); assertThat(payload.fieldNames().asSequence().toList()).containsExactlyInAnyOrder("payloadVersion", "resumeId", "targetJobId"); assertThat(cleaned.resultPayload).isNull()
        val cleanedAttemptJob = JobStatusResponse.from(store.findById(attemptJobId).orElseThrow()); assertThat(cleanedAttemptJob.inputRefs).isEqualTo(JobInputRefs(null, targetJob, practiceSet, attempt)); assertThat(cleanedAttemptJob.maxAttempts).isEqualTo(3); assertThat(store.findById(attemptJobId).orElseThrow().requestPayload.toString()).doesNotContain("PII_ANSWER_MARKER")
    }
    @Test @Order(7) fun activeFingerprintConflictReturnsNoInsertWithoutAbortingTheTransaction() {
        resetDomainTables(); val user = localUserService.localUserId(); val store = BackgroundJobStore(jdbcTemplate, objectMapper()); val payload: JsonNode = objectMapper().createObjectNode().put("payloadVersion", 1)
        assertThat(store.createIfAbsent(user, JobType.JOB_FIT, "job-fit", UUID.randomUUID(), payload, "same-fingerprint", 3)).isPresent(); assertThat(store.createIfAbsent(user, JobType.JOB_FIT, "job-fit", UUID.randomUUID(), payload, "same-fingerprint", 3)).isEmpty(); assertThat(store.findReusable(user, JobType.JOB_FIT, "same-fingerprint")).isPresent()
    }
    @Test @Order(9) fun deletingAResourcesJobsRemovesTheirEffectsAndRejectsInFlightOwnedLeaseWrites() {
        resetDomainTables(); val user = localUserService.localUserId(); val lease = UUID.randomUUID(); val resume = insertResume(user); val job = createProcessingJob(user, JobType.RESUME_SCORE, lease)
        jdbcTemplate.update("UPDATE ai_interview_app.background_jobs SET resource_id = ? WHERE id = ?", resume, job.id); materializationService.materializeResumeScore(job, lease, resume, score()); assertThat(count("background_job_effects")).isEqualTo(1)
        assertThat(BackgroundJobStore(jdbcTemplate, objectMapper()).deleteByResources("test", listOf(resume))).isEqualTo(1); assertThat(count("background_jobs")).isZero(); assertThat(count("background_job_effects")).isZero()
        assertThatThrownBy { materializationService.withOwnedLease(job, lease) { "write" } }.isInstanceOf(JobLeaseLostException::class.java); assertThatThrownBy { materializationService.materializeResumeScore(job, lease, resume, score()) }.isInstanceOf(JobLeaseLostException::class.java)
    }
    companion object {
        private val postgres = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")); internal lateinit var dataSource: DriverManagerDataSource; private lateinit var jdbcTemplate: JdbcTemplate; private lateinit var context: AnnotationConfigApplicationContext; private lateinit var materializationService: JobEffectMaterializationService; private lateinit var localUserService: LocalUserService; private lateinit var migratedReadyResumeId: UUID; private lateinit var migratedPendingResumeId: UUID; private lateinit var migratedFailedResumeId: UUID; private lateinit var migratedLegacyJobId: UUID
        @BeforeAll @JvmStatic fun setUpDatabase() { postgres.start(); dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password); jdbcTemplate = JdbcTemplate(dataSource); Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(MigrationVersion.fromVersion("5")).load().migrate(); seedMigrationFixtures(); Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate(); context = AnnotationConfigApplicationContext(TestConfiguration::class.java); materializationService = context.getBean(JobEffectMaterializationService::class.java); localUserService = context.getBean(LocalUserService::class.java) }
        @AfterAll @JvmStatic fun tearDownDatabase() { if (::context.isInitialized) context.close(); postgres.stop() }
        private fun seedMigrationFixtures() { val user = UUID.randomUUID(); migratedReadyResumeId = UUID.randomUUID(); migratedPendingResumeId = UUID.randomUUID(); migratedFailedResumeId = UUID.randomUUID(); migratedLegacyJobId = UUID.randomUUID(); jdbcTemplate.update("INSERT INTO ai_interview_app.app_users (id, email) VALUES (?, ?)", user, "migration@ai-interview.test"); jdbcTemplate.update("""INSERT INTO ai_interview_app.resumes (id, user_id, original_filename, normalized_text, parsed_skills) VALUES (?, ?, 'ready.txt', 'ready resume', '[]'::jsonb), (?, ?, 'pending.pdf', NULL, '[]'::jsonb), (?, ?, 'orphan.pdf', NULL, '[]'::jsonb)""", migratedReadyResumeId, user, migratedPendingResumeId, user, migratedFailedResumeId, user); jdbcTemplate.update("""INSERT INTO ai_interview_app.background_jobs (id, user_id, job_type, resource_type, resource_id, status, stage, request_payload, request_fingerprint, max_attempts) VALUES (?, ?, 'RESUME_EXTRACTION', 'resume', ?, 'QUEUED', 'QUEUED', jsonb_build_object('resumeId', ?::text), 'valid-extraction', 3)""", UUID.randomUUID(), user, migratedPendingResumeId, migratedPendingResumeId); jdbcTemplate.update("INSERT INTO ai_interview_app.background_jobs (id, job_type, status, stage, request_payload, max_attempts) VALUES (?, 'LEGACY_JOB', 'QUEUED', 'QUEUED', '{}'::jsonb, 3)", migratedLegacyJobId) }
        private fun resetDomainTables() { jdbcTemplate.execute("TRUNCATE TABLE ai_interview_app.background_job_effects, ai_interview_app.background_jobs, ai_interview_app.resume_scores, ai_interview_app.job_description_chunks, ai_interview_app.job_descriptions, ai_interview_app.resume_chunks, ai_interview_app.resumes, ai_interview_app.app_users CASCADE") }
        private fun createProcessingJob(user: UUID, type: JobType, lease: UUID): BackgroundJob { val id = UUID.randomUUID(); val payload: JsonNode = objectMapper().createObjectNode().put("payloadVersion", 1); jdbcTemplate.update("""INSERT INTO ai_interview_app.background_jobs (id, user_id, job_type, resource_type, status, stage, request_payload, request_fingerprint, attempts, max_attempts, lease_token, lease_expires_at, started_at) VALUES (?, ?, ?, 'test', 'PROCESSING', 'QUEUED', ?::jsonb, ?, 1, 3, ?, now() + interval '5 minutes', now())""", id, user, type.name, payload.toString(), UUID.randomUUID().toString().replace("-", ""), lease); val now = Instant.now(); return BackgroundJob(id, user, type, "test", null, JobStatus.PROCESSING, JobStage.QUEUED, payload, null, "test-fingerprint", 1, 3, null, null, null, now, now, now, now, now, null, lease, now.plusSeconds(300)) }
        private fun count(table: String) = jdbcTemplate.queryForObject("SELECT count(*) FROM ai_interview_app.$table", Int::class.java)!!; private fun resumeStatus(id: UUID) = jdbcTemplate.queryForObject("SELECT processing_status FROM ai_interview_app.resumes WHERE id = ?", String::class.java, id)!!
        private fun insertResume(user: UUID): UUID = UUID.randomUUID().also { jdbcTemplate.update("INSERT INTO ai_interview_app.resumes (id, user_id, name, source, processing_status, normalized_text) VALUES (?, ?, 'Backend', 'PASTE', 'READY', 'Built durable Spring services.')", it, user) }
        private fun score() = ResumeScoreResult(82, AssessmentScores(84, 80, 82, 83, 81), "Clear backend experience", emptyList(), emptyList(), null, Instant.now())
        private fun objectMapper() = if (::context.isInitialized) context.getBean(ObjectMapper::class.java) else ObjectMapper().findAndRegisterModules()
    }
}

@Configuration(proxyBeanMethods = true)
@EnableTransactionManagement
open class TestConfiguration {
    @Bean open fun dataSource() = JobEffectMaterializationIntegrationTests.run { dataSource }
    @Bean open fun jdbcTemplate(source: javax.sql.DataSource) = JdbcTemplate(source)
    @Bean open fun transactionManager(source: javax.sql.DataSource): PlatformTransactionManager = DataSourceTransactionManager(source)
    @Bean open fun objectMapper() = ObjectMapper().findAndRegisterModules()
    @Bean open fun localUserService(jdbc: JdbcTemplate) = LocalUserService(jdbc)
    @Bean open fun jobEffectMaterializationService(jdbc: JdbcTemplate, mapper: ObjectMapper) = JobEffectMaterializationService(jdbc, mapper)
}
