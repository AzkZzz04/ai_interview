package dev.jiaming.ai_interview.coach

import dev.jiaming.ai_interview.document.ResolvedDocument
import java.util.Optional

class CoachFeedbackInput(
    private val resumeValue: ResolvedDocument,
    jobDescriptionValue: Optional<ResolvedDocument>?,
    private val targetRoleValue: String?,
    private val seniorityValue: String?,
    private val questionTextValue: String?,
    private val categoryValue: String?,
    expectedSignalsValue: List<String>?,
    private val answerTextValue: String?
) {
    private val jobDescriptionValue = jobDescriptionValue ?: Optional.empty()
    private val expectedSignalsValue = expectedSignalsValue?.toList() ?: emptyList()
    fun resume() = resumeValue
    fun jobDescription() = jobDescriptionValue
    fun targetRole() = targetRoleValue
    fun seniority() = seniorityValue
    fun questionText() = questionTextValue
    fun category() = categoryValue
    fun expectedSignals() = expectedSignalsValue
    fun answerText() = answerTextValue

    override fun equals(other: Any?): Boolean = other is CoachFeedbackInput &&
        resumeValue == other.resumeValue && jobDescriptionValue == other.jobDescriptionValue &&
        targetRoleValue == other.targetRoleValue && seniorityValue == other.seniorityValue &&
        questionTextValue == other.questionTextValue && categoryValue == other.categoryValue &&
        expectedSignalsValue == other.expectedSignalsValue && answerTextValue == other.answerTextValue

    override fun hashCode(): Int = listOf(
        resumeValue, jobDescriptionValue, targetRoleValue, seniorityValue, questionTextValue,
        categoryValue, expectedSignalsValue, answerTextValue
    ).hashCode()

    override fun toString(): String =
        "CoachFeedbackInput[resume=$resumeValue, jobDescription=$jobDescriptionValue, targetRole=$targetRoleValue, seniority=$seniorityValue, questionText=$questionTextValue, category=$categoryValue, expectedSignals=$expectedSignalsValue, answerText=$answerTextValue]"
}
