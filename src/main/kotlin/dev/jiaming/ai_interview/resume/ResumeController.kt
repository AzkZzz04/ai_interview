package dev.jiaming.ai_interview.resume

import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/resumes")
class ResumeController(
    private val resumeUploadService: ResumeUploadService,
    private val resumeJobSubmissionService: ResumeJobSubmissionService,
    private val resumeLibraryService: ResumeLibraryService
) {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @RequestPart("file") file: MultipartFile,
        @RequestPart(value = "name", required = false) name: String?,
        @RequestPart(value = "jobTitle", required = false) jobTitle: String?
    ): ResponseEntity<ResumeCreated> = resumeJobSubmissionService.submit(file, name, jobTitle)

    @PostMapping("/paste", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun paste(@RequestBody request: PasteResumeRequest): ResponseEntity<ResumeCreated> = resumeLibraryService.paste(request)

    @GetMapping
    fun list(): ResumePage = resumeLibraryService.list()

    @GetMapping("/current")
    fun current(): ResumeUploadResponse = resumeUploadService.current()
        .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No resume has been uploaded") }

    @GetMapping("/{resumeId}")
    fun get(@PathVariable resumeId: UUID): ResumeLibraryDetail = resumeLibraryService.get(resumeId)

    @PatchMapping("/{resumeId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun patch(@PathVariable resumeId: UUID, @RequestBody body: JsonNode): ResumeLibraryItem = resumeLibraryService.patch(resumeId, body)

    @GetMapping("/{resumeId}/delete-impact")
    fun deleteImpact(@PathVariable resumeId: UUID) = resumeLibraryService.deleteImpact(resumeId)

    @DeleteMapping("/{resumeId}")
    fun delete(@PathVariable resumeId: UUID): ResponseEntity<Void> {
        resumeLibraryService.delete(resumeId)
        return ResponseEntity.noContent().build()
    }
}
