package dev.jiaming.ai_interview.fit

data class JobFitResult(
    val fitScore: Int,
    val summary: String,
    val matchedRequirements: List<MatchedRequirement>,
    val missingRequirements: List<MissingRequirement>,
    val feedback: List<FitFeedback>,
)

data class MatchedRequirement(val requirement: String, val evidence: String)

data class MissingRequirement(val requirement: String, val guidance: String)

data class FitFeedback(val priority: String, val message: String)
