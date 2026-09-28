package dev.jiaming.ai_interview.jobs

enum class JobStatus {
    QUEUED, PROCESSING, RETRYING, SUCCEEDED, PARTIAL, FAILED;

    fun terminal(): Boolean = this == SUCCEEDED || this == PARTIAL || this == FAILED
}
