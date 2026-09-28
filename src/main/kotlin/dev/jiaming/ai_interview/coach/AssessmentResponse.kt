package dev.jiaming.ai_interview.coach

@JvmRecord
data class AssessmentResponse(
    val overallScore: Int,
    val scores: AssessmentScores?,
    val strengths: List<String>?,
    val weaknesses: List<String>?,
    val recommendations: List<RecommendationResponse>?,
    val modelProvider: String?,
    val sourceContextIds: List<String>?
)
