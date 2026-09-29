package dev.jiaming.ai_interview.jobs

import java.util.UUID

@JvmRecord
data class AnalysisJobPayload(
    val payloadVersion: Int,
    val resumeId: UUID,
    val jobDescriptionId: UUID?,
    val targetRole: String?,
    val seniority: String?
) {
    constructor(resumeId: UUID, jobDescriptionId: UUID?, targetRole: String?, seniority: String?) :
        this(CURRENT_VERSION, resumeId, jobDescriptionId, targetRole, seniority)

    companion object { const val CURRENT_VERSION = 2 }
}
