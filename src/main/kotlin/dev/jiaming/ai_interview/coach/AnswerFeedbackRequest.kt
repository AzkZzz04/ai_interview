package dev.jiaming.ai_interview.coach

import java.util.UUID

@JvmRecord
data class AnswerFeedbackRequest(
    val resumeId: UUID?,
    val resumeText: String?,
    val jobDescriptionId: UUID?,
    val jobDescription: String?,
    val targetRole: String?,
    val seniority: String?,
    val questionText: String?,
    val category: String?,
    val expectedSignals: List<String>?,
    val answerText: String?
) {
    constructor(
        resumeText: String?, jobDescription: String?, targetRole: String?, seniority: String?,
        questionText: String?, category: String?, expectedSignals: List<String>?, answerText: String?
    ) : this(null, resumeText, null, jobDescription, targetRole, seniority, questionText, category, expectedSignals, answerText)
}
