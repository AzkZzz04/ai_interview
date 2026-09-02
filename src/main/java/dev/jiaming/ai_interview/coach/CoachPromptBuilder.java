package dev.jiaming.ai_interview.coach;

import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class CoachPromptBuilder {

	private static final int ANSWER_PROMPT_LIMIT = 4_000;

	public String buildAssessmentPrompt(CoachAnalysisInput input, CoachRagContext context) {
		SenioritySettings settings = SenioritySettings.forValue(input.seniority());
		return """
			You are a senior technical recruiter and engineering interviewer.
			Assess this tech resume for the target role using only the retrieved context below.
			Use job description context only if it appears in the retrieved context.
			Be direct, concrete, and evidence-based. Do not invent experience that is not in the retrieved context.
			If evidence is missing, mark it as a gap.
			For sourceContextIds, copy only exact contextId values shown in the retrieved context.
			Return only valid JSON matching this shape:
			{
			  "overallScore": 0,
			  "scores": {
			    "technicalDepth": 0,
			    "impact": 0,
			    "clarity": 0,
			    "relevance": 0,
			    "ats": 0
			  },
			  "strengths": ["2-4 concise strengths"],
			  "weaknesses": ["2-4 concise gaps"],
			  "recommendations": [
			    {"section": "Experience", "priority": "high", "message": "specific rewrite guidance"}
			  ],
			  "sourceContextIds": ["resume:experience:0"]
			}
			All scores must be integers from 0 to 100.

			Target role: %s
			Seniority: %s
			Seniority calibration: %s

			Retrieved context:
			%s
			""".formatted(
			fallback(input.targetRole(), "Software Engineer"),
			fallback(input.seniority(), "Mid-level"),
			settings.assessmentGuidance(),
			context.context()
		);
	}

	public String buildQuestionPrompt(CoachAnalysisInput input, CoachRagContext context) {
		SenioritySettings settings = SenioritySettings.forValue(input.seniority());
		return """
			You are generating a technical interview set for a candidate.
			Use only the retrieved resume and job-description context below.
			Create questions that test the candidate's actual claimed experience and the target role.
			Do not assume facts outside the retrieved context.
			For sourceContextIds, copy only exact contextId values shown in the retrieved context.
			Include a mix of resume deep dive, technical fundamentals, system design, project architecture, debugging, collaboration, and role-specific tooling.
			Return only valid JSON matching this shape:
			{
			  "questions": [
			    {
			      "id": "stable-slug",
			      "category": "Resume Deep Dive",
			      "difficulty": "Warmup",
			      "questionText": "question",
			      "expectedSignals": ["signal 1", "signal 2", "signal 3"],
			      "sourceContextIds": ["resume:projects:2"]
			    }
			  ]
			}
			Generate exactly 8 questions. Difficulty must be one of Warmup, Core, Deep Dive.

			Target role: %s
			Seniority: %s
			Seniority calibration: %s

			Retrieved context:
			%s
			""".formatted(
			fallback(input.targetRole(), "Software Engineer"),
			fallback(input.seniority(), "Mid-level"),
			settings.questionGuidance(),
			context.context()
		);
	}

	public String buildFeedbackPrompt(CoachFeedbackInput input, CoachRagContext context) {
		SenioritySettings settings = SenioritySettings.forValue(input.seniority());
		return """
			You are coaching a candidate after one interview answer.
			Score the answer against the question, expected signals, and retrieved context. Be specific and actionable.
			Do not reward claims that are not supported by the answer.
			Do not invent resume details beyond the retrieved context.
			For sourceContextIds, copy only exact contextId values shown in the retrieved context.
			Return only valid JSON matching this shape:
			{
			  "score": 0,
			  "summary": "one sentence",
			  "nextStep": "one concrete next practice step",
			  "strengths": ["1-3 strengths"],
			  "gaps": ["1-3 gaps"],
			  "betterAnswerOutline": ["context", "action", "tradeoff", "result"],
			  "followUpQuestion": "one follow-up question",
			  "sourceContextIds": ["resume:experience:0"]
			}
			Score must be an integer from 0 to 100.

			Target role: %s
			Seniority: %s
			Seniority calibration: %s
			Question category: %s
			Question: %s
			Expected signals: %s

			Retrieved context:
			%s

			Candidate answer:
			%s
			""".formatted(
			fallback(input.targetRole(), "Software Engineer"),
			fallback(input.seniority(), "Mid-level"),
			settings.feedbackGuidance(),
			fallback(input.category(), "Interview"),
			fallback(input.questionText(), ""),
			String.join(", ", safeList(input.expectedSignals())),
			context.context(),
			truncate(input.answerText(), ANSWER_PROMPT_LIMIT)
		);
	}

	public String buildRepairPrompt(String originalPrompt, String invalidOutput, String parseError) {
		return """
			Repair the JSON response below so it satisfies the original request exactly.
			Return only corrected JSON. Do not add markdown or commentary.

			Original request:
			%s

			Invalid response:
			%s

			Parser error:
			%s
			""".formatted(originalPrompt, invalidOutput, fallback(parseError, "JSON did not match the schema"));
	}

	private String truncate(String value, int limit) {
		String safeValue = fallback(value, "");
		if (safeValue.length() <= limit) {
			return safeValue;
		}
		return safeValue.substring(0, limit) + "\n[truncated]";
	}

	private String fallback(String value, String fallback) {
		return blank(value) ? fallback : value.trim();
	}

	private boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private <T> List<T> safeList(List<T> values) {
		return values == null ? List.of() : values;
	}
}
