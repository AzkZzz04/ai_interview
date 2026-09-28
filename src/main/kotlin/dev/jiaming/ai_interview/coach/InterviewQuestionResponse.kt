package dev.jiaming.ai_interview.coach

@JvmRecord
data class InterviewQuestionResponse(
    val id: String?, val category: String?, val difficulty: String?, val questionText: String?,
    val expectedSignals: List<String>?, val sourceContextIds: List<String>?
)
