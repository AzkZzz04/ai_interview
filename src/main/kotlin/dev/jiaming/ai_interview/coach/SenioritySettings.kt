package dev.jiaming.ai_interview.coach

import java.util.Locale

internal data class SenioritySettings(
    val assessmentGuidance: String, val questionGuidance: String, val feedbackGuidance: String, val retrievalTerms: String
) {
    companion object {
        private val DEFAULT = SenioritySettings(
            "Calibrate scoring and recommendations to the requested seniority and explicit job requirements.",
            "Calibrate question scope and depth to the requested seniority.",
            "Calibrate scoring and coaching to the requested seniority.", ""
        )
        private val INTERN = SenioritySettings(
            "Intern calibration: prioritize technical fundamentals, relevant coursework, projects, initiative, learning potential, and clear communication. Do not penalize missing senior-level architecture, organizational leadership, or years of production ownership unless the job description explicitly requires them.",
            "Intern calibration: generate exactly 4 Warmup, 3 Core, and 1 Deep Dive question. Focus on fundamentals, project decisions, debugging, testing, collaboration, and learning. Keep the Deep Dive scoped to a claimed project or bounded service; avoid organization-wide or Staff-level system design unless supported by the context.",
            "Intern calibration: reward correct fundamentals, structured reasoning, honest uncertainty, and coachability. Give a concrete learning-oriented next step and do not require senior-level leadership or scale experience unless the question explicitly asks for it.",
            "intern internship coursework education fundamentals projects debugging testing learning collaboration"
        )
        @JvmStatic fun forValue(seniority: String?): SenioritySettings =
            if (seniority?.trim()?.lowercase(Locale.ROOT) == "intern") INTERN else DEFAULT
    }
}
