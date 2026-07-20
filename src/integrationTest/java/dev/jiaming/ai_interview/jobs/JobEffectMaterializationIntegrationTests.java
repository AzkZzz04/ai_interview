package dev.jiaming.ai_interview.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import dev.jiaming.ai_interview.coach.AiAnalysisRequest;
import dev.jiaming.ai_interview.coach.AnswerFeedbackRequest;
import dev.jiaming.ai_interview.coach.AnswerFeedbackResponse;
import dev.jiaming.ai_interview.coach.AssessmentResponse;
import dev.jiaming.ai_interview.coach.AssessmentScores;
import dev.jiaming.ai_interview.coach.InterviewQuestionResponse;
import dev.jiaming.ai_interview.coach.InterviewQuestionsResponse;
import dev.jiaming.ai_interview.common.LocalUserService;
import dev.jiaming.ai_interview.interview.AnswerPersistenceService;
import dev.jiaming.ai_interview.interview.AssessmentPersistenceService;
import dev.jiaming.ai_interview.interview.InterviewPersistenceService;
import dev.jiaming.ai_interview.interview.InterviewSessionPersistenceService;
import dev.jiaming.ai_interview.interview.JobDescriptionPersistenceService;
import dev.jiaming.ai_interview.interview.PersistenceJsonSupport;
import dev.jiaming.ai_interview.resume.ResumePersistenceService;
import dev.jiaming.ai_interview.resume.ResumeTextNormalizer;
import dev.jiaming.ai_interview.resume.SectionAwareTextChunker;

@TestMethodOrder(OrderAnnotation.class)
class JobEffectMaterializationIntegrationTests {

	private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
		DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
	);

	private static DataSource dataSource;

	private static JdbcTemplate jdbcTemplate;

	private static AnnotationConfigApplicationContext context;

	private static JobEffectMaterializationService materializationService;

	private static LocalUserService localUserService;

	private static UUID migratedReadyResumeId;

	private static UUID migratedPendingResumeId;

	private static UUID migratedFailedResumeId;

	private static UUID migratedLegacyJobId;

	@BeforeAll
	static void setUpDatabase() {
		postgres.start();
		DriverManagerDataSource driverManagerDataSource = new DriverManagerDataSource(
			postgres.getJdbcUrl(),
			postgres.getUsername(),
			postgres.getPassword()
		);
		dataSource = driverManagerDataSource;
		jdbcTemplate = new JdbcTemplate(dataSource);

		Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration")
			.target(MigrationVersion.fromVersion("5"))
			.load()
			.migrate();
		seedMigrationFixtures();
		Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration")
			.load()
			.migrate();

		context = new AnnotationConfigApplicationContext(TestConfiguration.class);
		materializationService = context.getBean(JobEffectMaterializationService.class);
		localUserService = context.getBean(LocalUserService.class);
	}

	@AfterAll
	static void tearDownDatabase() {
		if (context != null) {
			context.close();
		}
		postgres.stop();
	}

	@Test
	@Order(1)
	void migrationQuarantinesLegacyJobsAndClassifiesExistingResumes() {
		assertThat(resumeStatus(migratedReadyResumeId)).isEqualTo("READY");
		assertThat(resumeStatus(migratedPendingResumeId)).isEqualTo("PENDING");
		assertThat(resumeStatus(migratedFailedResumeId)).isEqualTo("FAILED");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT failure_code FROM ai_interview_app.resumes WHERE id = ?",
			String.class,
			migratedFailedResumeId
		)).isEqualTo("LEGACY_RESUME_UNRESOLVED");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT status FROM ai_interview_app.background_jobs WHERE id = ?",
			String.class,
			migratedLegacyJobId
		)).isEqualTo("FAILED");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT error_code FROM ai_interview_app.background_jobs WHERE id = ?",
			String.class,
			migratedLegacyJobId
		)).isEqualTo("LEGACY_JOB_UNSUPPORTED");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT to_regclass('ai_interview_app.background_job_effects') IS NOT NULL",
			Boolean.class
		)).isTrue();
	}

	@Test
	@Order(2)
	void repeatedAssessmentAndQuestionMaterializationReusesOneContext() {
		resetDomainTables();
		UUID userId = localUserService.localUserId();
		UUID leaseToken = UUID.randomUUID();
		AiAnalysisRequest request = analysisRequest();
		BackgroundJob job = createProcessingJob(userId, JobType.ANALYSIS, leaseToken, request);

		UUID firstAssessment = materializationService.materializeAssessment(job, leaseToken, request, assessment());
		UUID secondAssessment = materializationService.materializeAssessment(job, leaseToken, request, assessment());
		UUID firstSession = materializationService.materializeQuestions(job, leaseToken, request, questions());
		UUID secondSession = materializationService.materializeQuestions(job, leaseToken, request, questions());

		assertThat(secondAssessment).isEqualTo(firstAssessment);
		assertThat(secondSession).isEqualTo(firstSession);
		assertThat(count("resume_assessments")).isEqualTo(1);
		assertThat(count("interview_sessions")).isEqualTo(1);
		assertThat(count("interview_questions")).isEqualTo(1);
		assertThat(count("resumes")).isEqualTo(1);
		assertThat(count("job_descriptions")).isEqualTo(1);
		assertThat(count("background_job_effects")).isEqualTo(2);

		UUID assessmentResumeId = jdbcTemplate.queryForObject(
			"SELECT resume_id FROM ai_interview_app.resume_assessments WHERE id = ?",
			UUID.class,
			firstAssessment
		);
		UUID sessionResumeId = jdbcTemplate.queryForObject(
			"SELECT resume_id FROM ai_interview_app.interview_sessions WHERE id = ?",
			UUID.class,
			firstSession
		);
		UUID sessionAssessmentId = jdbcTemplate.queryForObject(
			"SELECT assessment_id FROM ai_interview_app.interview_sessions WHERE id = ?",
			UUID.class,
			firstSession
		);
		assertThat(sessionResumeId).isEqualTo(assessmentResumeId);
		assertThat(sessionAssessmentId).isEqualTo(firstAssessment);
	}

	@Test
	@Order(3)
	void concurrentMaterializationCommitsOneAssessmentEffect() throws Exception {
		resetDomainTables();
		UUID userId = localUserService.localUserId();
		UUID leaseToken = UUID.randomUUID();
		AiAnalysisRequest request = analysisRequest();
		BackgroundJob job = createProcessingJob(userId, JobType.ANALYSIS, leaseToken, request);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Future<UUID> first = executor.submit(() -> {
				start.await();
				return materializationService.materializeAssessment(job, leaseToken, request, assessment());
			});
			Future<UUID> second = executor.submit(() -> {
				start.await();
				return materializationService.materializeAssessment(job, leaseToken, request, assessment());
			});
			start.countDown();

			assertThat(first.get()).isEqualTo(second.get());
			assertThat(count("resume_assessments")).isEqualTo(1);
			assertThat(count("background_job_effects")).isEqualTo(1);
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	@Order(4)
	void expiredOrReplacedLeaseCannotMaterializeAnEffect() {
		resetDomainTables();
		UUID userId = localUserService.localUserId();
		UUID storedLease = UUID.randomUUID();
		AiAnalysisRequest request = analysisRequest();
		BackgroundJob job = createProcessingJob(userId, JobType.ANALYSIS, storedLease, request);

		assertThatThrownBy(() -> materializationService.materializeAssessment(
			job,
			UUID.randomUUID(),
			request,
			assessment()
		)).isInstanceOf(JobLeaseLostException.class);

		jdbcTemplate.update(
			"UPDATE ai_interview_app.background_jobs SET lease_expires_at = now() - interval '1 second' WHERE id = ?",
			job.id()
		);
		assertThatThrownBy(() -> materializationService.materializeAssessment(job, storedLease, request, assessment()))
			.isInstanceOf(JobLeaseLostException.class);
		assertThat(count("background_job_effects")).isZero();
		assertThat(count("resume_assessments")).isZero();
	}

	@Test
	@Order(5)
	void repeatedFeedbackMaterializationCreatesOneAnswer() {
		resetDomainTables();
		UUID userId = localUserService.localUserId();
		UUID leaseToken = UUID.randomUUID();
		AnswerFeedbackRequest request = feedbackRequest();
		BackgroundJob job = createProcessingJob(userId, JobType.ANSWER_FEEDBACK, leaseToken, request);

		UUID firstAnswer = materializationService.materializeFeedback(job, leaseToken, request, feedback());
		UUID secondAnswer = materializationService.materializeFeedback(job, leaseToken, request, feedback());

		assertThat(secondAnswer).isEqualTo(firstAnswer);
		assertThat(count("interview_sessions")).isEqualTo(1);
		assertThat(count("interview_questions")).isEqualTo(1);
		assertThat(count("interview_answers")).isEqualTo(1);
		assertThat(count("background_job_effects")).isEqualTo(1);
	}

	private static void seedMigrationFixtures() {
		UUID userId = UUID.randomUUID();
		migratedReadyResumeId = UUID.randomUUID();
		migratedPendingResumeId = UUID.randomUUID();
		migratedFailedResumeId = UUID.randomUUID();
		migratedLegacyJobId = UUID.randomUUID();
		jdbcTemplate.update(
			"INSERT INTO ai_interview_app.app_users (id, email) VALUES (?, ?)",
			userId,
			"migration@ai-interview.test"
		);
		jdbcTemplate.update(
			"""
				INSERT INTO ai_interview_app.resumes (
					id, user_id, original_filename, normalized_text, parsed_skills
				) VALUES (?, ?, 'ready.txt', 'ready resume', '[]'::jsonb),
				         (?, ?, 'pending.pdf', NULL, '[]'::jsonb),
				         (?, ?, 'orphan.pdf', NULL, '[]'::jsonb)
				""",
			migratedReadyResumeId,
			userId,
			migratedPendingResumeId,
			userId,
			migratedFailedResumeId,
			userId
		);
		jdbcTemplate.update(
			"""
				INSERT INTO ai_interview_app.background_jobs (
					id, user_id, job_type, resource_type, resource_id, status, stage,
					request_payload, request_fingerprint, max_attempts
				) VALUES (?, ?, 'RESUME_EXTRACTION', 'resume', ?, 'QUEUED', 'QUEUED',
				          jsonb_build_object('resumeId', ?::text), 'valid-extraction', 3)
				""",
			UUID.randomUUID(),
			userId,
			migratedPendingResumeId,
			migratedPendingResumeId
		);
		jdbcTemplate.update(
			"""
				INSERT INTO ai_interview_app.background_jobs (
					id, job_type, status, stage, request_payload, max_attempts
				) VALUES (?, 'LEGACY_JOB', 'QUEUED', 'QUEUED', '{}'::jsonb, 3)
				""",
			migratedLegacyJobId
		);
	}

	private static void resetDomainTables() {
		jdbcTemplate.execute(
			"""
				TRUNCATE TABLE
					ai_interview_app.background_job_effects,
					ai_interview_app.background_jobs,
					ai_interview_app.interview_answers,
					ai_interview_app.interview_questions,
					ai_interview_app.interview_sessions,
					ai_interview_app.resume_assessments,
					ai_interview_app.job_description_chunks,
					ai_interview_app.job_descriptions,
					ai_interview_app.resume_chunks,
					ai_interview_app.resumes,
					ai_interview_app.app_users
				CASCADE
				"""
		);
	}

	private static BackgroundJob createProcessingJob(UUID userId, JobType type, UUID leaseToken, Object request) {
		UUID jobId = UUID.randomUUID();
		JsonNode requestPayload = objectMapper().valueToTree(request);
		jdbcTemplate.update(
			"""
				INSERT INTO ai_interview_app.background_jobs (
					id, user_id, job_type, resource_type, status, stage, request_payload,
					request_fingerprint, attempts, max_attempts, lease_token, lease_expires_at, started_at
				) VALUES (?, ?, ?, 'test', 'PROCESSING', 'QUEUED', ?::jsonb,
				          ?, 1, 3, ?, now() + interval '5 minutes', now())
				""",
			jobId,
			userId,
			type.name(),
			requestPayload.toString(),
			UUID.randomUUID().toString().replace("-", ""),
			leaseToken
		);
		Instant now = Instant.now();
		return new BackgroundJob(
			jobId,
			userId,
			type,
			"test",
			null,
			JobStatus.PROCESSING,
			JobStage.QUEUED,
			requestPayload,
			null,
			"test-fingerprint",
			1,
			3,
			null,
			null,
			null,
			now,
			now,
			now,
			now,
			now,
			null,
			leaseToken,
			now.plusSeconds(300)
		);
	}

	private static int count(String table) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM ai_interview_app." + table, Integer.class);
	}

	private static String resumeStatus(UUID resumeId) {
		return jdbcTemplate.queryForObject(
			"SELECT processing_status FROM ai_interview_app.resumes WHERE id = ?",
			String.class,
			resumeId
		);
	}

	private static AiAnalysisRequest analysisRequest() {
		return new AiAnalysisRequest(
			"EXPERIENCE\nBackend Engineer\nBuilt durable Spring services.",
			"Build reliable Java services.",
			"Backend Engineer",
			"Mid-level"
		);
	}

	private static AssessmentResponse assessment() {
		return new AssessmentResponse(
			82,
			new AssessmentScores(84, 80, 82, 83, 81),
			List.of("Clear backend experience"),
			List.of("Add metrics"),
			List.of(),
			"gemini",
			List.of("resume:0")
		);
	}

	private static InterviewQuestionsResponse questions() {
		return new InterviewQuestionsResponse(
			List.of(new InterviewQuestionResponse(
				"spring-design",
				"System Design",
				"Core",
				"How would you make this workflow idempotent?",
				List.of("Unique operation keys"),
				List.of("resume:0")
			)),
			"gemini"
		);
	}

	private static AnswerFeedbackRequest feedbackRequest() {
		return new AnswerFeedbackRequest(
			analysisRequest().resumeText(),
			analysisRequest().jobDescription(),
			analysisRequest().targetRole(),
			analysisRequest().seniority(),
			"How would you make this workflow idempotent?",
			"System Design",
			List.of("Unique operation keys"),
			"I would checkpoint output and use unique database effects."
		);
	}

	private static AnswerFeedbackResponse feedback() {
		return new AnswerFeedbackResponse(
			88,
			"Strong structure",
			"Add recovery details",
			List.of("Clear tradeoffs"),
			List.of("Missing metrics"),
			List.of("State assumptions", "Describe recovery"),
			"How would you test duplicate delivery?",
			"gemini",
			List.of("resume:0")
		);
	}

	private static ObjectMapper objectMapper() {
		return context == null
			? new ObjectMapper().findAndRegisterModules()
			: context.getBean(ObjectMapper.class);
	}

	@Configuration(proxyBeanMethods = true)
	@EnableTransactionManagement
	static class TestConfiguration {

		@Bean
		DataSource dataSource() {
			return JobEffectMaterializationIntegrationTests.dataSource;
		}

		@Bean
		JdbcTemplate jdbcTemplate(DataSource source) {
			return new JdbcTemplate(source);
		}

		@Bean
		PlatformTransactionManager transactionManager(DataSource source) {
			return new DataSourceTransactionManager(source);
		}

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper().findAndRegisterModules();
		}

		@Bean
		ResumeTextNormalizer resumeTextNormalizer() {
			return new ResumeTextNormalizer();
		}

		@Bean
		SectionAwareTextChunker sectionAwareTextChunker() {
			return new SectionAwareTextChunker();
		}

		@Bean
		LocalUserService localUserService(JdbcTemplate jdbc) {
			return new LocalUserService(jdbc);
		}

		@Bean
		ResumePersistenceService resumePersistenceService(JdbcTemplate jdbc, LocalUserService localUser) {
			return new ResumePersistenceService(jdbc, localUser);
		}

		@Bean
		PersistenceJsonSupport persistenceJsonSupport(ObjectMapper mapper) {
			return new PersistenceJsonSupport(mapper);
		}

		@Bean
		JobDescriptionPersistenceService jobDescriptionPersistenceService(
			JdbcTemplate jdbc,
			ResumeTextNormalizer normalizer,
			SectionAwareTextChunker chunker
		) {
			return new JobDescriptionPersistenceService(jdbc, normalizer, chunker);
		}

		@Bean
		AssessmentPersistenceService assessmentPersistenceService(
			JdbcTemplate jdbc,
			ResumePersistenceService resumes,
			SectionAwareTextChunker chunker,
			JobDescriptionPersistenceService jobDescriptions,
			PersistenceJsonSupport jsonSupport
		) {
			return new AssessmentPersistenceService(jdbc, resumes, chunker, jobDescriptions, jsonSupport);
		}

		@Bean
		InterviewSessionPersistenceService interviewSessionPersistenceService(
			JdbcTemplate jdbc,
			ResumePersistenceService resumes,
			SectionAwareTextChunker chunker,
			JobDescriptionPersistenceService jobDescriptions,
			PersistenceJsonSupport jsonSupport
		) {
			return new InterviewSessionPersistenceService(jdbc, resumes, chunker, jobDescriptions, jsonSupport);
		}

		@Bean
		AnswerPersistenceService answerPersistenceService(JdbcTemplate jdbc, PersistenceJsonSupport jsonSupport) {
			return new AnswerPersistenceService(jdbc, jsonSupport);
		}

		@Bean
		InterviewPersistenceService interviewPersistenceService(
			AssessmentPersistenceService assessments,
			InterviewSessionPersistenceService sessions,
			AnswerPersistenceService answers
		) {
			return new InterviewPersistenceService(assessments, sessions, answers);
		}

		@Bean
		JobEffectMaterializationService jobEffectMaterializationService(
			JdbcTemplate jdbc,
			InterviewPersistenceService persistence
		) {
			return new JobEffectMaterializationService(jdbc, persistence);
		}
	}
}
