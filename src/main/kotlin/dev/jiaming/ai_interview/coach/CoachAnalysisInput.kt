package dev.jiaming.ai_interview.coach

import dev.jiaming.ai_interview.document.ResolvedDocument
import java.util.Optional

class CoachAnalysisInput(
    private val resumeValue: ResolvedDocument,
    jobDescriptionValue: Optional<ResolvedDocument>?
) {
    private val jobDescriptionValue = jobDescriptionValue ?: Optional.empty()
    fun resume() = resumeValue
    fun jobDescription() = jobDescriptionValue

    override fun equals(other: Any?): Boolean = other is CoachAnalysisInput &&
        resumeValue == other.resumeValue && jobDescriptionValue == other.jobDescriptionValue

    override fun hashCode(): Int = listOf(resumeValue, jobDescriptionValue).hashCode()

    override fun toString(): String =
        "CoachAnalysisInput[resume=$resumeValue, jobDescription=$jobDescriptionValue]"
}
