package dev.jiaming.ai_interview.jobs

import java.util.UUID

@JvmRecord
data class JobFitPayload(
    val payloadVersion: Int,
    val fitId: UUID,
    val resumeId: UUID,
    val targetJobId: UUID,
) {
    constructor(fitId: UUID, resumeId: UUID, targetJobId: UUID) : this(CURRENT_VERSION, fitId, resumeId, targetJobId)

    companion object { const val CURRENT_VERSION = 1 }
}
