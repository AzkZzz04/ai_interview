package dev.jiaming.ai_interview.jobs

enum class JobStatus {
    QUEUED, PROCESSING, RETRYING, SUCCEEDED, FAILED;

    fun terminal(): Boolean = this == SUCCEEDED || this == FAILED
}
