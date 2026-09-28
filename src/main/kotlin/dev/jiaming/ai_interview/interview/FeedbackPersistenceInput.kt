package dev.jiaming.ai_interview.interview

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import dev.jiaming.ai_interview.coach.CoachFeedbackInput
import dev.jiaming.ai_interview.document.ResolvedDocument
import java.util.UUID

class FeedbackPersistenceInput @JsonCreator constructor(
    @param:JsonProperty("userId") @get:JsonProperty("userId") val userId: UUID?,
    @param:JsonProperty("resumeId") @get:JsonProperty("resumeId") val resumeId: UUID?,
    @param:JsonProperty("resumeHash") @get:JsonProperty("resumeHash") val resumeHash: String?,
    @param:JsonProperty("jobDescriptionId") @get:JsonProperty("jobDescriptionId") val jobDescriptionId: UUID?,
    @param:JsonProperty("jobDescriptionHash") @get:JsonProperty("jobDescriptionHash") val jobDescriptionHash: String?,
    @param:JsonProperty("targetRole") @get:JsonProperty("targetRole") val targetRole: String?,
    @param:JsonProperty("seniority") @get:JsonProperty("seniority") val seniority: String?,
    @param:JsonProperty("questionText") @get:JsonProperty("questionText") val questionText: String?,
    @param:JsonProperty("category") @get:JsonProperty("category") val category: String?,
    @JsonProperty("expectedSignals") expectedSignals: List<String>?,
    @param:JsonProperty("answerText") @get:JsonProperty("answerText") val answerText: String?,
) {
    @get:JsonProperty("expectedSignals")
    val expectedSignals: List<String> = expectedSignals?.let { java.util.List.copyOf(it) } ?: emptyList()

    fun userId(): UUID? = userId
    fun resumeId(): UUID? = resumeId
    fun resumeHash(): String? = resumeHash
    fun jobDescriptionId(): UUID? = jobDescriptionId
    fun jobDescriptionHash(): String? = jobDescriptionHash
    fun targetRole(): String? = targetRole
    fun seniority(): String? = seniority
    fun questionText(): String? = questionText
    fun category(): String? = category
    fun expectedSignals(): List<String> = expectedSignals
    fun answerText(): String? = answerText

    override fun equals(other: Any?): Boolean = other is FeedbackPersistenceInput &&
        userId == other.userId && resumeId == other.resumeId && resumeHash == other.resumeHash &&
        jobDescriptionId == other.jobDescriptionId && jobDescriptionHash == other.jobDescriptionHash && targetRole == other.targetRole &&
        seniority == other.seniority && questionText == other.questionText && category == other.category &&
        expectedSignals == other.expectedSignals && answerText == other.answerText

    override fun hashCode(): Int = listOf(
        userId, resumeId, resumeHash, jobDescriptionId, jobDescriptionHash,
        targetRole, seniority, questionText, category, expectedSignals, answerText,
    ).hashCode()

    override fun toString(): String =
        "FeedbackPersistenceInput[userId=$userId, resumeId=$resumeId, resumeHash=$resumeHash, " +
            "jobDescriptionId=$jobDescriptionId, jobDescriptionHash=$jobDescriptionHash, " +
            "targetRole=$targetRole, seniority=$seniority, questionText=$questionText, category=$category, " +
            "expectedSignals=$expectedSignals, answerText=$answerText]"

    companion object {
        @JvmStatic
        fun from(userId: UUID, input: CoachFeedbackInput) = FeedbackPersistenceInput(
            userId,
            input.resume().resourceId(),
            input.resume().contentHash(),
            input.jobDescription().map { it.resourceId() }.orElse(null),
            input.jobDescription().map { it.contentHash() }.orElse(""),
            input.targetRole(),
            input.seniority(),
            input.questionText(),
            input.category(),
            input.expectedSignals(),
            input.answerText(),
        )
    }
}
