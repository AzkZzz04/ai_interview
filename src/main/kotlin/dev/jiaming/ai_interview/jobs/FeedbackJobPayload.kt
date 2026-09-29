package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.annotation.JsonProperty
import java.util.ArrayList
import java.util.Collections
import java.util.UUID

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
) {
    init { expectedSignalsValue = Collections.unmodifiableList(ArrayList<String>(expectedSignalsValue ?: emptyList<String>())) }
    fun expectedSignals(): List<String> = expectedSignalsValue ?: emptyList()

    constructor(resumeId: UUID, jobDescriptionId: UUID?, targetRole: String?, seniority: String?, questionText: String?,
                category: String?, expectedSignals: List<String>?, answerText: String?) :
        this(CURRENT_VERSION, resumeId, jobDescriptionId, targetRole, seniority, questionText, category, expectedSignals, answerText)

    companion object { const val CURRENT_VERSION = 2 }
}
