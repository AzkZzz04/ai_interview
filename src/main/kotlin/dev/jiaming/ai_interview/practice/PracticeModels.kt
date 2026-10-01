package dev.jiaming.ai_interview.practice

import dev.jiaming.ai_interview.jobs.ActiveJob
import java.time.Instant
import java.util.UUID

/** Contract §7 `PracticeSet`. */
data class PracticeSetView(
    val id: UUID,
    val resumeId: UUID,
    val targetJobId: UUID,
    val mode: String,
    val status: PracticeSetStatus,
    val questions: List<PracticeQuestionView>,
    val activeJob: ActiveJob?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class PracticeSetStatus { GENERATING, READY, FAILED }

/** Contract §7 `Question`. Answer attempts arrive with U11; until then `attempts` is always empty. */
data class PracticeQuestionView(
    val id: UUID,
    val order: Int,
    val origin: String,
    val text: String,
    val rationale: String?,
    val category: String?,
    val expectedSignals: List<String>,
    val attempts: List<Any> = emptyList(),
)

/** One normalized AI question before it is saved. */
data class PracticeQuestionDraft(val text: String, val rationale: String, val category: String?, val expectedSignals: List<String>)

/** The generated questions, saved as the job checkpoint so a retry never calls the model again. */
data class PracticeQuestionDrafts(val drafts: List<PracticeQuestionDraft>)

/** The `PRACTICE_QUESTIONS` job result (contract §8.1). */
data class PracticeQuestionsResult(val questions: List<PracticeQuestionView>)

data class PracticeSetCreation(val set: PracticeSetView, val created: Boolean)

data class CreatePracticeSetRequest(val resumeId: UUID?, val targetJobId: UUID?, val mode: String?)

data class AddPracticeQuestionRequest(val text: String?)

@JvmRecord
data class PracticeQuestionsPayload(
    val payloadVersion: Int,
    val practiceSetId: UUID,
    val resumeId: UUID,
    val targetJobId: UUID,
) {
    constructor(practiceSetId: UUID, resumeId: UUID, targetJobId: UUID) : this(CURRENT_VERSION, practiceSetId, resumeId, targetJobId)

    companion object { const val CURRENT_VERSION = 1 }
}
