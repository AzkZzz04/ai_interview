package dev.jiaming.ai_interview.coach

@JvmRecord
data class AnswerFeedbackResponse(
    val score: Int,
    val summary: String?,
    val nextStep: String?,
    val strengths: List<String>?,
    val gaps: List<String>?,
    val betterAnswerOutline: List<String>?,
    val followUpQuestion: String?,
    val modelProvider: String?,
    val sourceContextIds: List<String>?
)
