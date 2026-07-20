package dev.jiaming.ai_interview.interview;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import dev.jiaming.ai_interview.coach.AiAnalysisRequest;
import dev.jiaming.ai_interview.coach.AssessmentResponse;
import dev.jiaming.ai_interview.resume.PersistedResume;
import dev.jiaming.ai_interview.resume.ResumeChunkResponse;
import dev.jiaming.ai_interview.resume.ResumePersistenceService;
import dev.jiaming.ai_interview.resume.SectionAwareTextChunker;

@Service
public class AssessmentPersistenceService {

	private final JdbcTemplate jdbcTemplate;

	private final ResumePersistenceService resumePersistenceService;

	private final SectionAwareTextChunker chunker;

	private final JobDescriptionPersistenceService jobDescriptionPersistenceService;

	private final PersistenceJsonSupport jsonSupport;

	public AssessmentPersistenceService(
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

	public void save(
		UUID assessmentId,
		UUID userId,
		AiAnalysisRequest request,
		String resumeText,
		AssessmentResponse response
	) {
		PersistedResume resume = resumeFor(resumeText);
		UUID jobDescriptionId = jobDescriptionPersistenceService.save(userId, request.jobDescription()).orElse(null);
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
			userId,
			resume.id(),
			jobDescriptionId,
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
			jsonSupport.hash(request.resumeText(), request.jobDescription(), request.targetRole(), request.seniority())
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
}
