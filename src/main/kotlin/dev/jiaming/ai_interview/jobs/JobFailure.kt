package dev.jiaming.ai_interview.jobs

@JvmRecord
data class JobFailure(val code: String, val message: String, val retryable: Boolean)
