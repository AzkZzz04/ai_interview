package dev.jiaming.ai_interview.jobs

import java.util.UUID

/** Feedback for one answer attempt (contract §7.5). Its job's resource is the attempt; the answer text stays on the attempt row. */
@JvmRecord
data class AttemptFeedbackPayload(
    val payloadVersion: Int,
    val attemptId: UUID,
    val practiceSetId: UUID,
    val resumeId: UUID,
    val targetJobId: UUID,
) {
    constructor(attemptId: UUID, practiceSetId: UUID, resumeId: UUID, targetJobId: UUID) :
        this(CURRENT_VERSION, attemptId, practiceSetId, resumeId, targetJobId)

    companion object {
        // Stored attempt jobs carry version 3; versions 1 and 2 belonged to the removed resume-backed feedback.
        const val CURRENT_VERSION = 3
        const val RESOURCE = "attempt"
    }
}
