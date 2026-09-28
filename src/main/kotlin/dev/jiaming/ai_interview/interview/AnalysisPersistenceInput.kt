package dev.jiaming.ai_interview.interview

import dev.jiaming.ai_interview.coach.CoachAnalysisInput
import java.util.UUID

@JvmRecord
data class AnalysisPersistenceInput(
    val userId: UUID?,
    val resumeId: UUID?,
    val resumeHash: String?,
    val jobDescriptionId: UUID?,
    val jobDescriptionHash: String?,
    val targetRole: String?,
    val seniority: String?,
) {
    companion object {
        @JvmStatic
        fun from(userId: UUID, input: CoachAnalysisInput) = AnalysisPersistenceInput(
            userId,
            input.resume().resourceId(),
            input.resume().contentHash(),
            input.jobDescription().map { it.resourceId() }.orElse(null),
            input.jobDescription().map { it.contentHash() }.orElse(""),
            input.targetRole(),
            input.seniority(),
        )
    }
}
