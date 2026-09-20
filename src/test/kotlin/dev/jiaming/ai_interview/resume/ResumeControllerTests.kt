package dev.jiaming.ai_interview.resume

import dev.jiaming.ai_interview.jobs.JobAcceptedResponse
import dev.jiaming.ai_interview.jobs.JobStage
import dev.jiaming.ai_interview.jobs.JobStatus
import dev.jiaming.ai_interview.jobs.JobType
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Optional
import java.util.UUID
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup

class ResumeControllerTests {
	private val service = Mockito.mock(ResumeUploadService::class.java)
	private val submissionService = Mockito.mock(ResumeJobSubmissionService::class.java)
	private val mockMvc = standaloneSetup(ResumeController(service, submissionService)).build()

	@Test
	fun uploadsResumeAndReturnsAcceptedJob() {
		val jobId = UUID.randomUUID()
		Mockito.`when`(submissionService.submit(any())).thenReturn(JobAcceptedResponse(jobId, JobType.RESUME_EXTRACTION, JobStatus.QUEUED, JobStage.QUEUED, "/api/jobs/$jobId", false))
		val file = MockMultipartFile("file", "resume.txt", "text/plain", "SKILLS\nJava Spring Boot".toByteArray(StandardCharsets.UTF_8))
		mockMvc.perform(multipart("/api/resumes").file(file))
			.andExpect(status().isAccepted)
			.andExpect(jsonPath("$.jobId").value(jobId.toString()))
			.andExpect(jsonPath("$.jobType").value("RESUME_EXTRACTION"))
			.andExpect(jsonPath("$.status").value("QUEUED"))
	}

	@Test
	fun returnsCurrentCompletedResume() {
		Mockito.`when`(service.current()).thenReturn(Optional.of(ResumeUploadResponse(UUID.randomUUID().toString(), "resume.txt", "text/plain", "text/plain", 24, 24, 24, "SKILLS\nJava Spring Boot", listOf(ResumeChunkResponse(0, "Skills", "Java Spring Boot", 16)), Instant.now())))
		mockMvc.perform(get("/api/resumes/current"))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.originalFilename").value("resume.txt"))
	}

	@Test
	fun returnsNotFoundWhenNoResumeHasBeenUploaded() {
		val emptyService = Mockito.mock(ResumeUploadService::class.java)
		Mockito.`when`(emptyService.current()).thenReturn(Optional.empty())
		val emptyMockMvc = standaloneSetup(ResumeController(emptyService, submissionService)).build()
		emptyMockMvc.perform(get("/api/resumes/current")).andExpect(status().isNotFound)
	}
}
