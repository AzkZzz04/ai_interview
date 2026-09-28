package dev.jiaming.ai_interview.coach

import dev.jiaming.ai_interview.document.ResolvedDocument
import java.util.Optional

class CoachAnalysisInput(
    private val resumeValue: ResolvedDocument,
    jobDescriptionValue: Optional<ResolvedDocument>?,
    private val targetRoleValue: String?,
    private val seniorityValue: String?
) {
    private val jobDescriptionValue = jobDescriptionValue ?: Optional.empty()
    fun resume() = resumeValue
    fun jobDescription() = jobDescriptionValue
    fun targetRole() = targetRoleValue
    fun seniority() = seniorityValue

    override fun equals(other: Any?): Boolean = other is CoachAnalysisInput &&
        resumeValue == other.resumeValue && jobDescriptionValue == other.jobDescriptionValue &&
        targetRoleValue == other.targetRoleValue && seniorityValue == other.seniorityValue

    override fun hashCode(): Int = listOf(resumeValue, jobDescriptionValue, targetRoleValue, seniorityValue).hashCode()

    override fun toString(): String =
        "CoachAnalysisInput[resume=$resumeValue, jobDescription=$jobDescriptionValue, targetRole=$targetRoleValue, seniority=$seniorityValue]"
}
