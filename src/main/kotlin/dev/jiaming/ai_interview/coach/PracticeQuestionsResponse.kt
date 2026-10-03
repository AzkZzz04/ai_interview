package dev.jiaming.ai_interview.coach

data class PracticeQuestionsResponse(val questions: List<PracticeQuestionResponse?>? = null)

data class PracticeQuestionResponse(
    val category: String? = null,
    val questionText: String? = null,
    val rationale: String? = null,
    val expectedSignals: List<String?>? = null,
)
