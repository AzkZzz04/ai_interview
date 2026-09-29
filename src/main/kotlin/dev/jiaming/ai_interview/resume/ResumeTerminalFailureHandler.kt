package dev.jiaming.ai_interview.resume

import dev.jiaming.ai_interview.jobs.BackgroundJob
import dev.jiaming.ai_interview.jobs.JobTerminalFailureHandler
import dev.jiaming.ai_interview.jobs.JobType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class ResumeTerminalFailureHandler(
    private val persistenceService: ResumePersistenceService,
    private val storageService: ResumeStorageService
) : JobTerminalFailureHandler {
    override fun supports(jobType: JobType) = jobType == JobType.RESUME_EXTRACTION

    override fun handle(job: BackgroundJob, errorCode: String, errorMessage: String) {
        val resumeId = job.resourceId
        if (resumeId == null) {
            log.warn("resume_failure_missing_resource jobId={}", job.id)
            return
        }
        persistenceService.markFailed(resumeId, errorCode, errorMessage).ifPresent { deleteAndClear(job.id, resumeId, it) }
    }

    private fun deleteAndClear(jobId: java.util.UUID, resumeId: java.util.UUID, storageKey: String) {
        storageService.delete(storageKey)
        persistenceService.clearStorageKey(resumeId, storageKey)
        log.info("resume_failed_object_deleted jobId={} resumeId={}", jobId, resumeId)
    }

    private companion object { val log = LoggerFactory.getLogger(ResumeTerminalFailureHandler::class.java) }
}
