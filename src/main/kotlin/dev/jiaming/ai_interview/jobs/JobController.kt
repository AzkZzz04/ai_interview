package dev.jiaming.ai_interview.jobs

import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.LocalUserService

@RestController
@RequestMapping("/api/jobs")
class JobController(private val jobStore: BackgroundJobStore, private val localUserService: LocalUserService) {
    @GetMapping("/{jobId}")
    fun status(@PathVariable jobId: UUID): JobStatusResponse = jobStore.findForUser(jobId, localUserService.localUserId())
        .map(JobStatusResponse::from)
        .orElseThrow { ApiRequestException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Background job was not found") }
}
