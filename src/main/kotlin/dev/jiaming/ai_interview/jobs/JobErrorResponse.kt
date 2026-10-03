package dev.jiaming.ai_interview.jobs

@JvmRecord
data class JobErrorResponse(val code: String?, val message: String, val retryable: Boolean?) {
    companion object {
        @JvmStatic fun from(job: BackgroundJob): JobErrorResponse? = job.lastError?.let { JobErrorResponse(job.errorCode, it, job.retryable) }
    }
}
