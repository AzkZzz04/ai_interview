package dev.jiaming.ai_interview.suggestions

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
@RequestMapping("/api/resumes/{resumeId}/target-jobs/{targetJobId}/suggestions")
class SuggestionsController(private val suggestionsService: SuggestionsService) {
    @GetMapping
    fun get(@PathVariable resumeId: UUID, @PathVariable targetJobId: UUID): SuggestionsView = suggestionsService.get(resumeId, targetJobId)

    @PostMapping
    fun run(@PathVariable resumeId: UUID, @PathVariable targetJobId: UUID): ResponseEntity<JobAcceptedResponse> =
        ResponseEntity.status(HttpStatus.ACCEPTED).body(suggestionsService.run(resumeId, targetJobId))
}
