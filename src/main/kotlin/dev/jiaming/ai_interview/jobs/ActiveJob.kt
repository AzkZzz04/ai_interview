package dev.jiaming.ai_interview.jobs

import java.util.UUID

/** The newest job for a resource, looked up with [BackgroundJobStore.findLatestForResource]. */
@JvmRecord
data class ActiveJob(
    val jobId: UUID, val jobType: JobType, val status: JobStatus, val stage: JobStage,
    val attempts: Int, val maxAttempts: Int, val error: JobErrorResponse?
) {
    companion object {
        @JvmStatic fun from(job: BackgroundJob) =
            ActiveJob(job.id, job.jobType, job.status, job.stage, job.attempts, job.maxAttempts, JobErrorResponse.from(job))
    }
}
