package dev.jiaming.ai_interview.coach

data class ExperienceSuggestionsResponse(val items: List<ExperienceSuggestionResponse?>? = null)

data class ExperienceSuggestionResponse(
    val requirement: String? = null,
    val sourceId: String? = null,
    val match: String? = null,
    val whyItFits: String? = null,
    val guidance: String? = null,
)
