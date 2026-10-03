package dev.jiaming.ai_interview.fit

import dev.jiaming.ai_interview.jobs.JobAcceptedResponse
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/resumes/{resumeId}/target-jobs/{targetJobId}/fit")
class FitController(private val jobFitService: JobFitService) {
    @GetMapping
    fun get(@PathVariable resumeId: UUID, @PathVariable targetJobId: UUID): FitView = jobFitService.get(resumeId, targetJobId)

    @PostMapping
    fun run(@PathVariable resumeId: UUID, @PathVariable targetJobId: UUID): ResponseEntity<JobAcceptedResponse> =
        ResponseEntity.status(HttpStatus.ACCEPTED).body(jobFitService.run(resumeId, targetJobId))
}
