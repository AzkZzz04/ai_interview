package dev.jiaming.ai_interview.interview;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import dev.jiaming.ai_interview.coach.InterviewQuestionResponse;
import dev.jiaming.ai_interview.coach.InterviewQuestionsResponse;

@Service
public class InterviewSessionPersistenceService {

	private final JdbcTemplate jdbcTemplate;

	private final PersistenceJsonSupport jsonSupport;

	public InterviewSessionPersistenceService(
		JdbcTemplate jdbcTemplate,
		PersistenceJsonSupport jsonSupport
	) {
		this.jdbcTemplate = jdbcTemplate;
		this.jsonSupport = jsonSupport;
	}

	public void saveQuestions(
		UUID sessionId,
		UUID assessmentId,
		AnalysisPersistenceInput input,
		InterviewQuestionsResponse response
	) {
		createSession(
			sessionId,
			input.userId(),
			input.resumeId(),
			input.jobDescriptionId(),
			assessmentId,
			input.targetRole(),
			input.seniority()
		);

		int index = 0;
		for (InterviewQuestionResponse question : response.questions()) {
			saveQuestion(sessionId, question, index++);
		}
	}

	public Optional<UUID> findLatestQuestion(FeedbackPersistenceInput input) {
		String questionText = input.questionText();
		if (questionText == null || questionText.isBlank()) {
			return Optional.empty();
		}
		List<UUID> questionIds = jdbcTemplate.query(
			"""
				SELECT q.id
				FROM ai_interview_app.interview_questions q
				JOIN ai_interview_app.interview_sessions s ON s.id = q.session_id
				WHERE s.user_id = ?
				  AND s.resume_id = ?
				  AND s.job_description_id IS NOT DISTINCT FROM ?
				  AND s.target_role IS NOT DISTINCT FROM ?
				  AND s.seniority IS NOT DISTINCT FROM ?
				  AND q.question_text = ?
				  AND q.category = ?
				ORDER BY q.created_at DESC
				LIMIT 1
				""",
			(rs, rowNum) -> rs.getObject("id", UUID.class),
			input.userId(),
			input.resumeId(),
			input.jobDescriptionId(),
			input.targetRole(),
			input.seniority(),
			questionText,
			category(input.category())
		);
		return questionIds.stream().findFirst();
	}

	public UUID createQuestionForAnswer(FeedbackPersistenceInput input) {
		UUID sessionId = UUID.randomUUID();
		createSession(
			sessionId,
			input.userId(),
			input.resumeId(),
			input.jobDescriptionId(),
			null,
			input.targetRole(),
			input.seniority()
		);
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
			input.questionText(),
			category(input.category()),
			"Core",
			jsonSupport.json(input.expectedSignals())
		);
		return questionId;
	}

	private String category(String value) {
		return value == null || value.isBlank() ? "Interview" : value;
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

}
