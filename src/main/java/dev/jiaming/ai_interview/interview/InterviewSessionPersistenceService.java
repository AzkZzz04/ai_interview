package dev.jiaming.ai_interview.interview;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import dev.jiaming.ai_interview.coach.AiAnalysisRequest;
import dev.jiaming.ai_interview.coach.AnswerFeedbackRequest;
import dev.jiaming.ai_interview.coach.InterviewQuestionResponse;
import dev.jiaming.ai_interview.coach.InterviewQuestionsResponse;
import dev.jiaming.ai_interview.resume.PersistedResume;
import dev.jiaming.ai_interview.resume.ResumeChunkResponse;
import dev.jiaming.ai_interview.resume.ResumePersistenceService;
import dev.jiaming.ai_interview.resume.SectionAwareTextChunker;

@Service
public class InterviewSessionPersistenceService {

	private final JdbcTemplate jdbcTemplate;

	private final ResumePersistenceService resumePersistenceService;

	private final SectionAwareTextChunker chunker;

	private final JobDescriptionPersistenceService jobDescriptionPersistenceService;

	private final PersistenceJsonSupport jsonSupport;

	public InterviewSessionPersistenceService(
		JdbcTemplate jdbcTemplate,
		ResumePersistenceService resumePersistenceService,
		SectionAwareTextChunker chunker,
		JobDescriptionPersistenceService jobDescriptionPersistenceService,
		PersistenceJsonSupport jsonSupport
	) {
		this.jdbcTemplate = jdbcTemplate;
		this.resumePersistenceService = resumePersistenceService;
		this.chunker = chunker;
		this.jobDescriptionPersistenceService = jobDescriptionPersistenceService;
		this.jsonSupport = jsonSupport;
	}

	public void saveQuestions(
		UUID sessionId,
		UUID userId,
		UUID assessmentId,
		AiAnalysisRequest request,
		InterviewQuestionsResponse response
	) {
		AssessmentContext context = assessmentContext(userId, assessmentId);
		createSession(
			sessionId,
			userId,
			context.resumeId(),
			context.jobDescriptionId(),
			assessmentId,
			request.targetRole(),
			request.seniority()
		);

		int index = 0;
		for (InterviewQuestionResponse question : response.questions()) {
			saveQuestion(sessionId, question, index++);
		}
	}

	public Optional<UUID> findLatestQuestion(UUID userId, String questionText) {
		if (questionText == null || questionText.isBlank()) {
			return Optional.empty();
		}
		List<UUID> questionIds = jdbcTemplate.query(
			"""
				SELECT q.id
				FROM ai_interview_app.interview_questions q
				JOIN ai_interview_app.interview_sessions s ON s.id = q.session_id
				WHERE s.user_id = ? AND q.question_text = ?
				ORDER BY q.created_at DESC
				LIMIT 1
				""",
			(rs, rowNum) -> rs.getObject("id", UUID.class),
			userId,
			questionText
		);
		return questionIds.stream().findFirst();
	}

	public UUID createQuestionForAnswer(UUID userId, AnswerFeedbackRequest request, String resumeText) {
		PersistedResume resume = resumeFor(resumeText);
		UUID jobDescriptionId = jobDescriptionPersistenceService.save(userId, request.jobDescription()).orElse(null);
		UUID sessionId = UUID.randomUUID();
		createSession(sessionId, userId, resume.id(), jobDescriptionId, null, request.targetRole(), request.seniority());
		UUID questionId = UUID.randomUUID();
		jdbcTemplate.update(
			"""
				INSERT INTO ai_interview_app.interview_questions (
					id, session_id, question_text, category, difficulty,
					expected_signals, source_context, order_index
				)
				VALUES (?, ?, ?, ?, ?, ?::jsonb, '[]'::jsonb, 0)
				""",
			questionId,
			sessionId,
			request.questionText(),
			request.category() == null || request.category().isBlank() ? "Interview" : request.category(),
			"Core",
			jsonSupport.json(request.expectedSignals())
		);
		return questionId;
	}

	private void createSession(
		UUID sessionId,
		UUID userId,
		UUID resumeId,
		UUID jobDescriptionId,
		UUID assessmentId,
		String targetRole,
		String seniority
	) {
		jdbcTemplate.update(
			"""
				INSERT INTO ai_interview_app.interview_sessions (
					id, user_id, resume_id, job_description_id, assessment_id, target_role, seniority, status
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, 'READY')
				""",
			sessionId,
			userId,
			resumeId,
			jobDescriptionId,
			assessmentId,
			targetRole,
			seniority
		);
	}

	private AssessmentContext assessmentContext(UUID userId, UUID assessmentId) {
		List<AssessmentContext> contexts = jdbcTemplate.query(
			"""
				SELECT resume_id, job_description_id
				FROM ai_interview_app.resume_assessments
				WHERE id = ? AND user_id = ?
				""",
			(rs, rowNum) -> new AssessmentContext(
				rs.getObject("resume_id", UUID.class),
				rs.getObject("job_description_id", UUID.class)
			),
			assessmentId,
			userId
		);
		if (contexts.isEmpty()) {
			throw new IllegalStateException("Assessment context does not exist: " + assessmentId);
		}
		return contexts.getFirst();
	}

	private void saveQuestion(UUID sessionId, InterviewQuestionResponse question, int orderIndex) {
		jdbcTemplate.update(
			"""
				INSERT INTO ai_interview_app.interview_questions (
					id, session_id, question_text, category, difficulty,
					expected_signals, source_context, order_index
				)
				VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
				""",
			UUID.randomUUID(),
			sessionId,
			question.questionText(),
			question.category(),
			question.difficulty(),
			jsonSupport.json(question.expectedSignals()),
			jsonSupport.json(question.sourceContextIds()),
			orderIndex
		);
	}

	private PersistedResume resumeFor(String resumeText) {
		return resumePersistenceService.findOrCreateTextResume(
			resumeText,
			chunker.chunk(resumeText).stream()
				.map(chunk -> new ResumeChunkResponse(
					chunk.index(),
					chunk.section(),
					chunk.content(),
					chunk.content().length()
				))
				.toList()
		);
	}

	private record AssessmentContext(UUID resumeId, UUID jobDescriptionId) {
	}
}
