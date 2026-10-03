package dev.jiaming.ai_interview.coach

@JvmRecord
data class ExperienceSplitResponse(val items: List<ExperienceSplitResponseItem?>?)

@JvmRecord
data class ExperienceSplitResponseItem(
    val title: String?,
    val organization: String?,
    val startDate: String?,
    val endDate: String?,
    val description: String?
)
