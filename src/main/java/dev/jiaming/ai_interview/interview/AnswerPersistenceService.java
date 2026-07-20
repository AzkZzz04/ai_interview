package dev.jiaming.ai_interview.interview;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import dev.jiaming.ai_interview.coach.AnswerFeedbackRequest;
import dev.jiaming.ai_interview.coach.AnswerFeedbackResponse;

@Service
public class AnswerPersistenceService {

	private final JdbcTemplate jdbcTemplate;

	private final PersistenceJsonSupport jsonSupport;

	public AnswerPersistenceService(JdbcTemplate jdbcTemplate, PersistenceJsonSupport jsonSupport) {
		this.jdbcTemplate = jdbcTemplate;
		this.jsonSupport = jsonSupport;
	}

	public void save(UUID answerId, UUID questionId, AnswerFeedbackRequest request, AnswerFeedbackResponse response) {
		jdbcTemplate.update(
			"""
				INSERT INTO ai_interview_app.interview_answers (
					id, question_id, answer_text, score, feedback, model_name, prompt_name
				)
				VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
				""",
			answerId,
			questionId,
			request.answerText(),
			response.score(),
			jsonSupport.json(Map.of(
				"summary", response.summary(),
				"nextStep", response.nextStep(),
				"strengths", response.strengths(),
				"gaps", response.gaps(),
				"betterAnswerOutline", response.betterAnswerOutline(),
				"followUpQuestion", response.followUpQuestion(),
				"sourceContextIds", response.sourceContextIds()
			)),
			jsonSupport.model(response.modelProvider()),
			jsonSupport.promptName()
		);
	}
}
