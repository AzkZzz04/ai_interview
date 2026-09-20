package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.coach.*
import dev.jiaming.ai_interview.common.*
import dev.jiaming.ai_interview.interview.*
import dev.jiaming.ai_interview.resume.*
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
import org.testcontainers.containers.PostgreSQLContainer
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
    @Test @Order(2) fun repeatedAssessmentAndQuestionMaterializationReusesOneContext() {
        resetDomainTables(); val user = localUserService.localUserId(); val lease = UUID.randomUUID(); val request = analysisRequest(); val input = analysisInput(user, request); val job = createProcessingJob(user, JobType.ANALYSIS, lease, request)
        val firstAssessment = materializationService.materializeAssessment(job, lease, input, assessment()); val secondAssessment = materializationService.materializeAssessment(job, lease, input, assessment()); val firstSession = materializationService.materializeQuestions(job, lease, input, questions()); val secondSession = materializationService.materializeQuestions(job, lease, input, questions())
        assertThat(secondAssessment).isEqualTo(firstAssessment); assertThat(secondSession).isEqualTo(firstSession); assertThat(count("resume_assessments")).isEqualTo(1); assertThat(count("interview_sessions")).isEqualTo(1); assertThat(count("interview_questions")).isEqualTo(1); assertThat(count("resumes")).isEqualTo(1); assertThat(count("job_descriptions")).isEqualTo(1); assertThat(count("background_job_effects")).isEqualTo(2)
        val assessmentResume = jdbcTemplate.queryForObject("SELECT resume_id FROM ai_interview_app.resume_assessments WHERE id = ?", UUID::class.java, firstAssessment); val sessionResume = jdbcTemplate.queryForObject("SELECT resume_id FROM ai_interview_app.interview_sessions WHERE id = ?", UUID::class.java, firstSession); val sessionAssessment = jdbcTemplate.queryForObject("SELECT assessment_id FROM ai_interview_app.interview_sessions WHERE id = ?", UUID::class.java, firstSession)
        assertThat(sessionResume).isEqualTo(assessmentResume); assertThat(sessionAssessment).isEqualTo(firstAssessment)
    }
    @Test @Order(3) fun concurrentMaterializationCommitsOneAssessmentEffect() {
        resetDomainTables(); val user = localUserService.localUserId(); val lease = UUID.randomUUID(); val request = analysisRequest(); val input = analysisInput(user, request); val job = createProcessingJob(user, JobType.ANALYSIS, lease, request); val start = CountDownLatch(1); val executor = Executors.newFixedThreadPool(2)
        try { val first = executor.submit<UUID> { start.await(); materializationService.materializeAssessment(job, lease, input, assessment()) }; val second = executor.submit<UUID> { start.await(); materializationService.materializeAssessment(job, lease, input, assessment()) }; start.countDown(); assertThat(first.get()).isEqualTo(second.get()); assertThat(count("resume_assessments")).isEqualTo(1); assertThat(count("background_job_effects")).isEqualTo(1) } finally { executor.shutdownNow() }
    }
    @Test @Order(4) fun expiredOrReplacedLeaseCannotMaterializeAnEffect() {
        resetDomainTables(); val user = localUserService.localUserId(); val storedLease = UUID.randomUUID(); val request = analysisRequest(); val input = analysisInput(user, request); val job = createProcessingJob(user, JobType.ANALYSIS, storedLease, request)
        assertThatThrownBy { materializationService.materializeAssessment(job, UUID.randomUUID(), input, assessment()) }.isInstanceOf(JobLeaseLostException::class.java)
        jdbcTemplate.update("UPDATE ai_interview_app.background_jobs SET lease_expires_at = now() - interval '1 second' WHERE id = ?", job.id()); assertThatThrownBy { materializationService.materializeAssessment(job, storedLease, input, assessment()) }.isInstanceOf(JobLeaseLostException::class.java); assertThat(count("background_job_effects")).isZero(); assertThat(count("resume_assessments")).isZero()
    }
    @Test @Order(5) fun repeatedFeedbackMaterializationCreatesOneAnswer() {
        resetDomainTables(); val user = localUserService.localUserId(); val lease = UUID.randomUUID(); val request = feedbackRequest(); val input = feedbackInput(user, request); val job = createProcessingJob(user, JobType.ANSWER_FEEDBACK, lease, request); val first = materializationService.materializeFeedback(job, lease, input, feedback()); val second = materializationService.materializeFeedback(job, lease, input, feedback())
        assertThat(second).isEqualTo(first); assertThat(count("interview_sessions")).isEqualTo(1); assertThat(count("interview_questions")).isEqualTo(1); assertThat(count("interview_answers")).isEqualTo(1); assertThat(count("background_job_effects")).isEqualTo(1)
    }
    @Test @Order(6) fun payloadCleanupRetainsOnlyStableInputReferences() {
        resetDomainTables(); val user = localUserService.localUserId(); val resume = UUID.randomUUID(); val jd = UUID.randomUUID(); val jobId = UUID.randomUUID()
        jdbcTemplate.update("""INSERT INTO ai_interview_app.background_jobs (id, user_id, job_type, resource_type, resource_id, status, stage, request_payload, result_payload, max_attempts, completed_at) VALUES (?, ?, 'ANALYSIS', 'resume', ?, 'SUCCEEDED', 'COMPLETED', jsonb_build_object('payloadVersion', 2, 'resumeId', ?::text, 'jobDescriptionId', ?::text, 'resumeText', 'PII_RESUME_MARKER', 'jobDescription', 'PII_JOB_MARKER', 'targetRole', 'Backend Engineer'), jsonb_build_object('assessment', jsonb_build_object('overallScore', 90)), 3, now() - interval '8 days')""", jobId, user, resume, resume, jd)
        val store = BackgroundJobStore(jdbcTemplate, objectMapper()); assertThat(store.clearExpiredPayloads(7)).isEqualTo(1); val cleaned = store.findById(jobId).orElseThrow(); assertThat(JobInputRefs.from(cleaned)).isEqualTo(JobInputRefs(resume, jd)); assertThat(cleaned.requestPayload().toString()).doesNotContain("PII_RESUME_MARKER", "PII_JOB_MARKER"); assertThat(cleaned.requestPayload().fieldNames().asSequence().toList()).containsExactlyInAnyOrder("payloadVersion", "resumeId", "jobDescriptionId"); assertThat(cleaned.resultPayload()).isNull()
    }
    @Test @Order(7) fun activeFingerprintConflictReturnsNoInsertWithoutAbortingTheTransaction() {
        resetDomainTables(); val user = localUserService.localUserId(); val store = BackgroundJobStore(jdbcTemplate, objectMapper()); val payload: JsonNode = objectMapper().createObjectNode().put("payloadVersion", 2)
        assertThat(store.createIfAbsent(user, JobType.ANALYSIS, "resume", UUID.randomUUID(), payload, "same-fingerprint", 3)).isPresent(); assertThat(store.createIfAbsent(user, JobType.ANALYSIS, "resume", UUID.randomUUID(), payload, "same-fingerprint", 3)).isEmpty(); assertThat(store.findReusable(user, JobType.ANALYSIS, "same-fingerprint", java.time.Duration.ofMinutes(5))).isPresent()
    }
    companion object {
        private val postgres = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")); internal lateinit var dataSource: DriverManagerDataSource; private lateinit var jdbcTemplate: JdbcTemplate; private lateinit var context: AnnotationConfigApplicationContext; private lateinit var materializationService: JobEffectMaterializationService; private lateinit var localUserService: LocalUserService; private lateinit var migratedReadyResumeId: UUID; private lateinit var migratedPendingResumeId: UUID; private lateinit var migratedFailedResumeId: UUID; private lateinit var migratedLegacyJobId: UUID
        @BeforeAll @JvmStatic fun setUpDatabase() { postgres.start(); dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password); jdbcTemplate = JdbcTemplate(dataSource); Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(MigrationVersion.fromVersion("5")).load().migrate(); seedMigrationFixtures(); Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate(); context = AnnotationConfigApplicationContext(TestConfiguration::class.java); materializationService = context.getBean(JobEffectMaterializationService::class.java); localUserService = context.getBean(LocalUserService::class.java) }
        @AfterAll @JvmStatic fun tearDownDatabase() { if (::context.isInitialized) context.close(); postgres.stop() }
        private fun seedMigrationFixtures() { val user = UUID.randomUUID(); migratedReadyResumeId = UUID.randomUUID(); migratedPendingResumeId = UUID.randomUUID(); migratedFailedResumeId = UUID.randomUUID(); migratedLegacyJobId = UUID.randomUUID(); jdbcTemplate.update("INSERT INTO ai_interview_app.app_users (id, email) VALUES (?, ?)", user, "migration@ai-interview.test"); jdbcTemplate.update("""INSERT INTO ai_interview_app.resumes (id, user_id, original_filename, normalized_text, parsed_skills) VALUES (?, ?, 'ready.txt', 'ready resume', '[]'::jsonb), (?, ?, 'pending.pdf', NULL, '[]'::jsonb), (?, ?, 'orphan.pdf', NULL, '[]'::jsonb)""", migratedReadyResumeId, user, migratedPendingResumeId, user, migratedFailedResumeId, user); jdbcTemplate.update("""INSERT INTO ai_interview_app.background_jobs (id, user_id, job_type, resource_type, resource_id, status, stage, request_payload, request_fingerprint, max_attempts) VALUES (?, ?, 'RESUME_EXTRACTION', 'resume', ?, 'QUEUED', 'QUEUED', jsonb_build_object('resumeId', ?::text), 'valid-extraction', 3)""", UUID.randomUUID(), user, migratedPendingResumeId, migratedPendingResumeId); jdbcTemplate.update("INSERT INTO ai_interview_app.background_jobs (id, job_type, status, stage, request_payload, max_attempts) VALUES (?, 'LEGACY_JOB', 'QUEUED', 'QUEUED', '{}'::jsonb, 3)", migratedLegacyJobId) }
        private fun resetDomainTables() { jdbcTemplate.execute("TRUNCATE TABLE ai_interview_app.background_job_effects, ai_interview_app.background_jobs, ai_interview_app.interview_answers, ai_interview_app.interview_questions, ai_interview_app.interview_sessions, ai_interview_app.resume_assessments, ai_interview_app.job_description_chunks, ai_interview_app.job_descriptions, ai_interview_app.resume_chunks, ai_interview_app.resumes, ai_interview_app.app_users CASCADE") }
        private fun createProcessingJob(user: UUID, type: JobType, lease: UUID, request: Any): BackgroundJob { val id = UUID.randomUUID(); val payload = objectMapper().valueToTree<JsonNode>(request); jdbcTemplate.update("""INSERT INTO ai_interview_app.background_jobs (id, user_id, job_type, resource_type, status, stage, request_payload, request_fingerprint, attempts, max_attempts, lease_token, lease_expires_at, started_at) VALUES (?, ?, ?, 'test', 'PROCESSING', 'QUEUED', ?::jsonb, ?, 1, 3, ?, now() + interval '5 minutes', now())""", id, user, type.name, payload.toString(), UUID.randomUUID().toString().replace("-", ""), lease); val now = Instant.now(); return BackgroundJob(id, user, type, "test", null, JobStatus.PROCESSING, JobStage.QUEUED, payload, null, "test-fingerprint", 1, 3, null, null, null, now, now, now, now, now, null, lease, now.plusSeconds(300)) }
        private fun count(table: String) = jdbcTemplate.queryForObject("SELECT count(*) FROM ai_interview_app.$table", Int::class.java)!!; private fun resumeStatus(id: UUID) = jdbcTemplate.queryForObject("SELECT processing_status FROM ai_interview_app.resumes WHERE id = ?", String::class.java, id)!!
        private fun analysisRequest() = AiAnalysisRequest("EXPERIENCE\nBackend Engineer\nBuilt durable Spring services.", "Build reliable Java services.", "Backend Engineer", "Mid-level")
        private fun analysisInput(user: UUID, request: AiAnalysisRequest): AnalysisPersistenceInput { val resume = context.getBean(ResumePersistenceService::class.java).findOrCreateDocument(user, request.resumeText()); val jd = context.getBean(JobDescriptionPersistenceService::class.java).findOrCreateDocument(user, request.jobDescription()); return AnalysisPersistenceInput(user, resume.resourceId(), resume.contentHash(), jd.resourceId(), jd.contentHash(), request.targetRole(), request.seniority()) }
        private fun assessment() = AssessmentResponse(82, AssessmentScores(84, 80, 82, 83, 81), listOf("Clear backend experience"), listOf("Add metrics"), emptyList(), "gemini", listOf("resume:0")); private fun questions() = InterviewQuestionsResponse(listOf(InterviewQuestionResponse("spring-design", "System Design", "Core", "How would you make this workflow idempotent?", listOf("Unique operation keys"), listOf("resume:0"))), "gemini")
        private fun feedbackRequest() = AnswerFeedbackRequest(analysisRequest().resumeText(), analysisRequest().jobDescription(), analysisRequest().targetRole(), analysisRequest().seniority(), "How would you make this workflow idempotent?", "System Design", listOf("Unique operation keys"), "I would checkpoint output and use unique database effects.")
        private fun feedbackInput(user: UUID, request: AnswerFeedbackRequest): FeedbackPersistenceInput { val resume = context.getBean(ResumePersistenceService::class.java).findOrCreateDocument(user, request.resumeText()); val jd = context.getBean(JobDescriptionPersistenceService::class.java).findOrCreateDocument(user, request.jobDescription()); return FeedbackPersistenceInput(user, resume.resourceId(), resume.contentHash(), jd.resourceId(), jd.contentHash(), request.targetRole(), request.seniority(), request.questionText(), request.category(), request.expectedSignals(), request.answerText()) }
        private fun feedback() = AnswerFeedbackResponse(88, "Strong structure", "Add recovery details", listOf("Clear tradeoffs"), listOf("Missing metrics"), listOf("State assumptions", "Describe recovery"), "How would you test duplicate delivery?", "gemini", listOf("resume:0")); private fun objectMapper() = if (::context.isInitialized) context.getBean(ObjectMapper::class.java) else ObjectMapper().findAndRegisterModules()
    }
}

@Configuration(proxyBeanMethods = true)
@EnableTransactionManagement
open class TestConfiguration {
    @Bean open fun dataSource() = JobEffectMaterializationIntegrationTests.run { dataSource }
    @Bean open fun jdbcTemplate(source: javax.sql.DataSource) = JdbcTemplate(source)
    @Bean open fun transactionManager(source: javax.sql.DataSource): PlatformTransactionManager = DataSourceTransactionManager(source)
    @Bean open fun objectMapper() = ObjectMapper().findAndRegisterModules()
    @Bean open fun resumeTextNormalizer() = ResumeTextNormalizer(); @Bean open fun sectionAwareTextChunker() = SectionAwareTextChunker(); @Bean open fun contentHasher() = ContentHasher(); @Bean open fun localUserService(jdbc: JdbcTemplate) = LocalUserService(jdbc)
    @Bean open fun resumePersistenceService(jdbc: JdbcTemplate, local: LocalUserService, normalizer: ResumeTextNormalizer, chunker: SectionAwareTextChunker, hasher: ContentHasher) = ResumePersistenceService(jdbc, local, normalizer, chunker, hasher)
    @Bean open fun persistenceJsonSupport(mapper: ObjectMapper) = PersistenceJsonSupport(mapper)
    @Bean open fun jobDescriptionPersistenceService(jdbc: JdbcTemplate, normalizer: ResumeTextNormalizer, chunker: SectionAwareTextChunker, hasher: ContentHasher) = JobDescriptionPersistenceService(jdbc, normalizer, chunker, hasher)
    @Bean open fun assessmentPersistenceService(jdbc: JdbcTemplate, json: PersistenceJsonSupport) = AssessmentPersistenceService(jdbc, json)
    @Bean open fun interviewSessionPersistenceService(jdbc: JdbcTemplate, json: PersistenceJsonSupport) = InterviewSessionPersistenceService(jdbc, json)
    @Bean open fun answerPersistenceService(jdbc: JdbcTemplate, json: PersistenceJsonSupport) = AnswerPersistenceService(jdbc, json)
    @Bean open fun interviewPersistenceService(assessments: AssessmentPersistenceService, sessions: InterviewSessionPersistenceService, answers: AnswerPersistenceService) = InterviewPersistenceService(assessments, sessions, answers)
    @Bean open fun jobEffectMaterializationService(jdbc: JdbcTemplate, persistence: InterviewPersistenceService) = JobEffectMaterializationService(jdbc, persistence)
}
