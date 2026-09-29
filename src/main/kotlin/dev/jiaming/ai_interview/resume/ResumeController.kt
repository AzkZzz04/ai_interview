package dev.jiaming.ai_interview.resume

import dev.jiaming.ai_interview.jobs.JobAcceptedResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/resumes")
@CrossOrigin(origins = ["http://localhost:3000", "http://127.0.0.1:3000"])
class ResumeController(
    private val resumeUploadService: ResumeUploadService,
    private val resumeJobSubmissionService: ResumeJobSubmissionService
) {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun upload(@RequestPart("file") file: MultipartFile): JobAcceptedResponse = resumeJobSubmissionService.submit(file)

    @GetMapping("/current")
    fun current(): ResumeUploadResponse = resumeUploadService.current()
        .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No resume has been uploaded") }
}
