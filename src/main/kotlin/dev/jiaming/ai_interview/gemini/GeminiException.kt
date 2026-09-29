package dev.jiaming.ai_interview.gemini

class GeminiException private constructor(
    val code: String,
    message: String,
    cause: Throwable?,
    val statusCode: Int?,
    val retryable: Boolean
) : RuntimeException(message, cause) {
    fun code() = code
    fun statusCode() = statusCode
    fun retryable() = retryable
    constructor(message: String) : this(GeminiErrorCode.UPSTREAM_ERROR, message, null, null, true)
    constructor(message: String, cause: Throwable?) : this(GeminiErrorCode.UPSTREAM_ERROR, message, cause, null, true)
    constructor(message: String, statusCode: Int, retryable: Boolean) : this(codeForStatus(statusCode), message, null, statusCode, retryable)
    constructor(code: String, message: String, retryable: Boolean) : this(code, message, null, null, retryable)
    constructor(code: String, message: String, cause: Throwable?, retryable: Boolean) : this(code, message, cause, null, retryable)
    constructor(code: String, message: String, statusCode: Int?, retryable: Boolean) : this(code, message, null, statusCode, retryable)

    companion object {
        private fun codeForStatus(statusCode: Int) =
            if (statusCode == 429) GeminiErrorCode.RATE_LIMITED else GeminiErrorCode.UPSTREAM_ERROR
    }
}
