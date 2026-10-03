package dev.jiaming.ai_interview.coach

data class JobFitResponse(
    val fitScore: Int? = null,
    val summary: String? = null,
    val matchedRequirements: List<MatchedRequirementResponse?>? = null,
    val missingRequirements: List<MissingRequirementResponse?>? = null,
    val feedback: List<FitFeedbackResponse?>? = null,
)

data class MatchedRequirementResponse(val requirement: String? = null, val evidence: String? = null)

data class MissingRequirementResponse(val requirement: String? = null, val guidance: String? = null)

data class FitFeedbackResponse(val priority: String? = null, val message: String? = null)
