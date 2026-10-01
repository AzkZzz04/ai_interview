package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.annotation.JsonProperty
import java.util.ArrayList
import java.util.Collections
import java.util.UUID

/** An `ANSWER_FEEDBACK` job input: an answer attempt, or a legacy resume-backed answer from the removed interview flow. */
sealed interface AnswerFeedbackJobPayload

/** Legacy resume-backed answer feedback (payloadVersion 2, upgraded from unversioned requests). Kept until U13 settles queued legacy jobs. */
data class FeedbackJobPayload(
    val payloadVersion: Int,
    val resumeId: UUID,
    val jobDescriptionId: UUID?,
    val targetRole: String?,
    val seniority: String?,
    val questionText: String?,
    val category: String?,
    @param:JsonProperty("expectedSignals")
    @field:JsonProperty("expectedSignals")
    private var expectedSignalsValue: List<String>?,
    val answerText: String?
) : AnswerFeedbackJobPayload {
    init { expectedSignalsValue = Collections.unmodifiableList(ArrayList<String>(expectedSignalsValue ?: emptyList<String>())) }
    fun expectedSignals(): List<String> = expectedSignalsValue ?: emptyList()

    constructor(resumeId: UUID, jobDescriptionId: UUID?, targetRole: String?, seniority: String?, questionText: String?,
                category: String?, expectedSignals: List<String>?, answerText: String?) :
        this(CURRENT_VERSION, resumeId, jobDescriptionId, targetRole, seniority, questionText, category, expectedSignals, answerText)

    companion object { const val CURRENT_VERSION = 2 }
}

/** Feedback for one answer attempt (contract §7.5). Its job's resource is the attempt; the answer text stays on the attempt row. */
@JvmRecord
data class AttemptFeedbackPayload(
    val payloadVersion: Int,
    val attemptId: UUID,
    val practiceSetId: UUID,
    val resumeId: UUID,
    val targetJobId: UUID,
) : AnswerFeedbackJobPayload {
    constructor(attemptId: UUID, practiceSetId: UUID, resumeId: UUID, targetJobId: UUID) :
        this(CURRENT_VERSION, attemptId, practiceSetId, resumeId, targetJobId)

    companion object {
        // Distinct from the legacy payloadVersion 2 so neither shape can be read as the other.
        const val CURRENT_VERSION = 3
        const val RESOURCE = "attempt"
    }
}
