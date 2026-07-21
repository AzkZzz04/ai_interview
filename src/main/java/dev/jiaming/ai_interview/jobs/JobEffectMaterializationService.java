package dev.jiaming.ai_interview.jobs;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.jiaming.ai_interview.coach.AnswerFeedbackResponse;
import dev.jiaming.ai_interview.coach.AssessmentResponse;
import dev.jiaming.ai_interview.coach.InterviewQuestionsResponse;
import dev.jiaming.ai_interview.interview.AnalysisPersistenceInput;
import dev.jiaming.ai_interview.interview.FeedbackPersistenceInput;
import dev.jiaming.ai_interview.interview.InterviewPersistenceService;

@Service
public class JobEffectMaterializationService {

	private final JdbcTemplate jdbcTemplate;

	private final InterviewPersistenceService interviewPersistenceService;

	public JobEffectMaterializationService(
		JdbcTemplate jdbcTemplate,
		InterviewPersistenceService interviewPersistenceService
	) {
		this.jdbcTemplate = jdbcTemplate;
		this.interviewPersistenceService = interviewPersistenceService;
	}

	@Transactional
	public UUID materializeAssessment(
		BackgroundJob job,
		UUID leaseToken,
		AnalysisPersistenceInput input,
		AssessmentResponse response
	) {
		lockOwnedLease(job.id(), leaseToken);
		return materialize(job.id(), JobEffectType.ASSESSMENT, assessmentId ->
			interviewPersistenceService.saveAssessment(
				assessmentId,
				input,
				response
			)
		);
	}

	@Transactional
	public UUID materializeQuestions(
		BackgroundJob job,
		UUID leaseToken,
		AnalysisPersistenceInput input,
		InterviewQuestionsResponse response
	) {
		lockOwnedLease(job.id(), leaseToken);
		UUID assessmentId = requireEffect(job.id(), JobEffectType.ASSESSMENT);
		return materialize(job.id(), JobEffectType.QUESTIONS, sessionId ->
			interviewPersistenceService.saveQuestions(
				sessionId,
				assessmentId,
				input,
				response
			)
		);
	}

	@Transactional
	public UUID materializeFeedback(
		BackgroundJob job,
		UUID leaseToken,
		FeedbackPersistenceInput input,
		AnswerFeedbackResponse response
	) {
		lockOwnedLease(job.id(), leaseToken);
		return materialize(job.id(), JobEffectType.ANSWER_FEEDBACK, answerId ->
			interviewPersistenceService.saveAnswer(
				answerId,
				input,
				response
			)
		);
	}

	@Transactional
	public <T> T withOwnedLease(BackgroundJob job, UUID leaseToken, Supplier<T> work) {
		lockOwnedLease(job.id(), leaseToken);
		return work.get();
	}

	private UUID materialize(UUID jobId, JobEffectType effectType, Consumer<UUID> writer) {
		UUID existing = findEffect(jobId, effectType);
		if (existing != null) {
			return existing;
		}

		UUID resourceId = UUID.randomUUID();
		int inserted = jdbcTemplate.update(
			"""
				INSERT INTO ai_interview_app.background_job_effects (job_id, effect_type, resource_id)
				VALUES (?, ?, ?)
				ON CONFLICT (job_id, effect_type) DO NOTHING
				""",
			jobId,
			effectType.name(),
			resourceId
		);
		if (inserted == 0) {
			return requireEffect(jobId, effectType);
		}

		writer.accept(resourceId);
		return resourceId;
	}

	private void lockOwnedLease(UUID jobId, UUID leaseToken) {
		List<UUID> jobs = jdbcTemplate.query(
			"""
				SELECT id
				FROM ai_interview_app.background_jobs
				WHERE id = ?
				  AND status = 'PROCESSING'
				  AND lease_token = ?
				  AND lease_expires_at > now()
				FOR UPDATE
				""",
			(rs, rowNum) -> rs.getObject("id", UUID.class),
			jobId,
			leaseToken
		);
		if (jobs.isEmpty()) {
			throw new JobLeaseLostException(jobId);
		}
	}

	private UUID requireEffect(UUID jobId, JobEffectType effectType) {
		UUID resourceId = findEffect(jobId, effectType);
		if (resourceId == null) {
			throw new IllegalStateException("Missing " + effectType + " effect for job " + jobId);
		}
		return resourceId;
	}

	private UUID findEffect(UUID jobId, JobEffectType effectType) {
		List<UUID> effects = jdbcTemplate.query(
			"""
				SELECT resource_id
				FROM ai_interview_app.background_job_effects
				WHERE job_id = ? AND effect_type = ?
				""",
			(rs, rowNum) -> rs.getObject("resource_id", UUID.class),
			jobId,
			effectType.name()
		);
		return effects.isEmpty() ? null : effects.getFirst();
	}

}
