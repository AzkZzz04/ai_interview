package dev.jiaming.ai_interview.assessment

import dev.jiaming.ai_interview.coach.AiAnalysisRequest
import dev.jiaming.ai_interview.jobs.JobAcceptedResponse
import dev.jiaming.ai_interview.jobs.JobSubmissionService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/assessments")
class AssessmentController(private val jobSubmissionService: JobSubmissionService) {
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun assess(@RequestBody request: AiAnalysisRequest): JobAcceptedResponse =
        jobSubmissionService.submitAnalysis(request)
}
