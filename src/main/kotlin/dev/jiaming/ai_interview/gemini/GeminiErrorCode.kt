package dev.jiaming.ai_interview.gemini

object GeminiErrorCode {
    const val NOT_CONFIGURED = "GEMINI_NOT_CONFIGURED"
    const val RATE_LIMITED = "GEMINI_RATE_LIMITED"
    const val TIMEOUT = "GEMINI_TIMEOUT"
    const val UPSTREAM_ERROR = "GEMINI_UPSTREAM_ERROR"
    const val SAFETY = "GEMINI_SAFETY"
    const val RECITATION = "GEMINI_RECITATION"
    const val MAX_TOKENS = "GEMINI_MAX_TOKENS"
    const val EMPTY_RESPONSE = "GEMINI_EMPTY_RESPONSE"
    const val INVALID_RESPONSE = "GEMINI_INVALID_RESPONSE"
}
