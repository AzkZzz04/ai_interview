package dev.jiaming.ai_interview.coach

@JvmRecord
data class ResumeScoreDraftResponse(
    val overall: Int?,
    val scores: AssessmentScores?,
    val summary: String?,
    val fixes: List<ResumeScoreFixDraft>?,
    val rewrites: List<ResumeScoreRewriteDraft>?
)

@JvmRecord
data class ResumeScoreFixDraft(val section: String?, val priority: String?, val message: String?)

@JvmRecord
data class ResumeScoreRewriteDraft(val section: String?, val original: String?, val rewritten: String?)
