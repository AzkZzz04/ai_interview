package dev.jiaming.ai_interview.interview;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import dev.jiaming.ai_interview.coach.AssessmentResponse;

@Service
public class AssessmentPersistenceService {

	private final JdbcTemplate jdbcTemplate;

	private final PersistenceJsonSupport jsonSupport;

	public AssessmentPersistenceService(
		JdbcTemplate jdbcTemplate,
		PersistenceJsonSupport jsonSupport
	) {
		this.jdbcTemplate = jdbcTemplate;
		this.jsonSupport = jsonSupport;
	}

	public void save(
		UUID assessmentId,
		AnalysisPersistenceInput input,
		AssessmentResponse response
	) {
		jdbcTemplate.update(
			"""
				INSERT INTO ai_interview_app.resume_assessments (
					id, user_id, resume_id, job_description_id, overall_score,
					technical_depth_score, impact_score, clarity_score, relevance_score, ats_score,
					strengths, weaknesses, recommendations, model_name, prompt_name, input_hash
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?)
				""",
			assessmentId,
			input.userId(),
			input.resumeId(),
			input.jobDescriptionId(),
			response.overallScore(),
			response.scores().technicalDepth(),
			response.scores().impact(),
			response.scores().clarity(),
			response.scores().relevance(),
			response.scores().ats(),
			jsonSupport.json(response.strengths()),
			jsonSupport.json(response.weaknesses()),
			jsonSupport.json(response.recommendations()),
			jsonSupport.model(response.modelProvider()),
			jsonSupport.promptName(),
			jsonSupport.hash(
				input.resumeHash(),
				input.jobDescriptionHash(),
				input.targetRole(),
				input.seniority()
			)
		);
	}
}
