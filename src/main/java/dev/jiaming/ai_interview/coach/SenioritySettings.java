package dev.jiaming.ai_interview.coach;

import java.util.Locale;

record SenioritySettings(
	String assessmentGuidance,
	String questionGuidance,
	String feedbackGuidance,
	String retrievalTerms
) {

	private static final SenioritySettings DEFAULT = new SenioritySettings(
		"Calibrate scoring and recommendations to the requested seniority and explicit job requirements.",
		"Calibrate question scope and depth to the requested seniority.",
		"Calibrate scoring and coaching to the requested seniority.",
		""
	);

	private static final SenioritySettings INTERN = new SenioritySettings(
		"Intern calibration: prioritize technical fundamentals, relevant coursework, projects, initiative, "
			+ "learning potential, and clear communication. Do not penalize missing senior-level architecture, "
			+ "organizational leadership, or years of production ownership unless the job description explicitly requires them.",
		"Intern calibration: generate exactly 4 Warmup, 3 Core, and 1 Deep Dive question. Focus on fundamentals, "
			+ "project decisions, debugging, testing, collaboration, and learning. Keep the Deep Dive scoped to a claimed "
			+ "project or bounded service; avoid organization-wide or Staff-level system design unless supported by the context.",
		"Intern calibration: reward correct fundamentals, structured reasoning, honest uncertainty, and coachability. "
			+ "Give a concrete learning-oriented next step and do not require senior-level leadership or scale experience "
			+ "unless the question explicitly asks for it.",
		"intern internship coursework education fundamentals projects debugging testing learning collaboration"
	);

	static SenioritySettings forValue(String seniority) {
		if (seniority == null) {
			return DEFAULT;
		}
		return "intern".equals(seniority.trim().toLowerCase(Locale.ROOT)) ? INTERN : DEFAULT;
	}
}
