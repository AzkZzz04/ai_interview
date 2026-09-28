package dev.jiaming.ai_interview.coach

@JvmRecord
data class InterviewQuestionsResponse(val questions: List<InterviewQuestionResponse>?, val modelProvider: String?)
