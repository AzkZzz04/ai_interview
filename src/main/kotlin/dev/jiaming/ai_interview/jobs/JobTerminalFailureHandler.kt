package dev.jiaming.ai_interview.jobs

interface JobTerminalFailureHandler {
    fun supports(jobType: JobType): Boolean
    fun handle(job: BackgroundJob, errorCode: String, errorMessage: String)
}
