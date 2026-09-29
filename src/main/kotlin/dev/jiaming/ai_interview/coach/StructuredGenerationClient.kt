package dev.jiaming.ai_interview.coach

/** Provider-neutral boundary for JSON-only model generation. */
interface StructuredGenerationClient {
    fun generateJson(prompt: String): String
}
