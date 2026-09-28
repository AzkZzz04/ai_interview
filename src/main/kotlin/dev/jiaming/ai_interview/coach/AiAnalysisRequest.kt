package dev.jiaming.ai_interview.coach

import java.util.UUID

@JvmRecord
data class AiAnalysisRequest(
    val resumeId: UUID?,
    val resumeText: String?,
    val jobDescriptionId: UUID?,
    val jobDescription: String?,
    val targetRole: String?,
    val seniority: String?
) {
    constructor(resumeText: String?, jobDescription: String?, targetRole: String?, seniority: String?) :
        this(null, resumeText, null, jobDescription, targetRole, seniority)
}
