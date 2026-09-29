package dev.jiaming.ai_interview.coach

@JvmRecord
data class CoachRagContext(val contextKey: String, val context: String, val sourceContextIds: List<String>, val vectorBacked: Boolean)
