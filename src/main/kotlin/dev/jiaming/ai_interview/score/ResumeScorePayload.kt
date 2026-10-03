package dev.jiaming.ai_interview.score

import java.util.UUID

@JvmRecord
data class ResumeScorePayload(
    val payloadVersion: Int,
    val resumeId: UUID,
    val resumeText: String,
    val jobTitle: String?
) {
    constructor(resumeId: UUID, resumeText: String, jobTitle: String?) : this(CURRENT_VERSION, resumeId, resumeText, jobTitle)

    companion object { const val CURRENT_VERSION = 1 }
}
