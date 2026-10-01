package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.common.ApiExceptionHandler
import dev.jiaming.ai_interview.common.LocalUserService
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.Mockito
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class AsyncJobControllerTests {
	private val submissionService = Mockito.mock(JobSubmissionService::class.java)

	@Test
	fun analysisSubmissionReturnsAcceptedJob() {
		val accepted = accepted(JobType.ANALYSIS)
		Mockito.`when`(submissionService.submitAnalysis(any())).thenReturn(accepted)
		val mockMvc = standaloneSetup(AnalysisController(submissionService)).build()

		mockMvc.perform(post("/api/analyses").contentType(MediaType.APPLICATION_JSON).content("""{"resumeText":"Java","jobDescription":"Spring","targetRole":"Backend Engineer","seniority":"Mid-level"}"""))
			.andExpect(status().isAccepted)
			.andExpect(jsonPath("$.jobType").value("ANALYSIS"))
			.andExpect(jsonPath("$.status").value("QUEUED"))
	}

	@Test
	fun jobStatusReturnsResultAndTimestamps() {
		val userId = UUID.randomUUID()
		val reader = Mockito.mock(JobStatusReader::class.java)
		val localUserService = Mockito.mock(LocalUserService::class.java)
		val job = completedJob(userId)
		Mockito.`when`(localUserService.localUserId()).thenReturn(userId)
		Mockito.`when`(reader.findForUser(job.id, userId)).thenReturn(JobStatusResponse.from(job))
		val mockMvc = standaloneSetup(JobController(reader, localUserService)).build()

		mockMvc.perform(get("/api/jobs/{jobId}", job.id))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.jobId").value(job.id.toString()))
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.result.overallScore").value(84))
			.andExpect(jsonPath("$.completedAt").exists())
			.andExpect(jsonPath("$.maxAttempts").value(3))
			.andExpect(jsonPath("$.inputRefs.resumeId").value(REFS[0].toString()))
			.andExpect(jsonPath("$.inputRefs.targetJobId").value(REFS[1].toString()))
			.andExpect(jsonPath("$.inputRefs.practiceSetId").value(REFS[2].toString()))
			.andExpect(jsonPath("$.inputRefs.attemptId").value(REFS[3].toString()))
		Mockito.verify(reader).findForUser(job.id, userId)
	}

	@Test
	fun jobStatusReturnsJobNotFoundForAMissingJob() {
		val userId = UUID.randomUUID()
		val reader = Mockito.mock(JobStatusReader::class.java)
		val localUserService = Mockito.mock(LocalUserService::class.java)
		Mockito.`when`(localUserService.localUserId()).thenReturn(userId)
		val jobId = UUID.randomUUID()
		Mockito.`when`(reader.findForUser(jobId, userId)).thenReturn(null)
		val mockMvc = standaloneSetup(JobController(reader, localUserService)).setControllerAdvice(ApiExceptionHandler()).build()

		mockMvc.perform(get("/api/jobs/{jobId}", jobId))
			.andExpect(status().isNotFound)
			.andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"))
	}

	private fun accepted(type: JobType): JobAcceptedResponse {
		val id = UUID.randomUUID()
		return JobAcceptedResponse(id, type, JobStatus.QUEUED, JobStage.QUEUED, "/api/jobs/$id", false)
	}

	private fun completedJob(userId: UUID): BackgroundJob {
		val now = Instant.now()
		return BackgroundJob(UUID.randomUUID(), userId, JobType.ANALYSIS, "resume", null, JobStatus.SUCCEEDED, JobStage.COMPLETED, ObjectMapper().createObjectNode().put("resumeId", REFS[0].toString()).put("targetJobId", REFS[1].toString()).put("practiceSetId", REFS[2].toString()).put("attemptId", REFS[3].toString()), ObjectMapper().createObjectNode().put("overallScore", 84), "fingerprint", 1, 3, null, null, false, now, now, now, now, now, now, null, null)
	}

	private companion object { val REFS = List(4) { UUID.randomUUID() } }
}
