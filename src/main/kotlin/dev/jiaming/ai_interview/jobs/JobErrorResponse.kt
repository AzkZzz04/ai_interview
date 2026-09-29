package dev.jiaming.ai_interview.jobs

@JvmRecord
data class JobErrorResponse(val code: String?, val message: String, val retryable: Boolean?)
