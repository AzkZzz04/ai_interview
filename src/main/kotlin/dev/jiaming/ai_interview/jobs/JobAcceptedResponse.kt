package dev.jiaming.ai_interview.jobs

import java.util.UUID

@JvmRecord
data class JobAcceptedResponse(
    val jobId: UUID, val jobType: JobType, val status: JobStatus, val stage: JobStage,
    val statusUrl: String, val reused: Boolean, val inputRefs: JobInputRefs
) {
    constructor(jobId: UUID, jobType: JobType, status: JobStatus, stage: JobStage, statusUrl: String, reused: Boolean) :
        this(jobId, jobType, status, stage, statusUrl, reused, JobInputRefs(null, null))

    companion object {
        @JvmStatic fun from(job: BackgroundJob, reused: Boolean) = JobAcceptedResponse(
            job.id, job.jobType, job.status, job.stage, "/api/jobs/${job.id}", reused, JobInputRefs.from(job)
        )
    }
}
