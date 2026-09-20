package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.interview.InterviewController
import java.time.Instant
import java.util.Optional
import java.util.UUID
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
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
	fun feedbackSubmissionReturnsAcceptedJob() {
		val accepted = accepted(JobType.ANSWER_FEEDBACK)
		Mockito.`when`(submissionService.submitFeedback(any())).thenReturn(accepted)
		val mockMvc = standaloneSetup(InterviewController(submissionService)).build()

		mockMvc.perform(post("/api/interview/feedback").contentType(MediaType.APPLICATION_JSON).content("""{"resumeText":"Java","jobDescription":"Spring","targetRole":"Backend Engineer","seniority":"Mid-level","questionText":"Explain a service","category":"Technical","expectedSignals":["trade-offs"],"answerText":"I built it with Spring Boot."}"""))
			.andExpect(status().isAccepted)
			.andExpect(jsonPath("$.jobType").value("ANSWER_FEEDBACK"))
	}

	@Test
	fun jobStatusReturnsResultAndTimestamps() {
		val userId = UUID.randomUUID()
		val store = Mockito.mock(BackgroundJobStore::class.java)
		val localUserService = Mockito.mock(LocalUserService::class.java)
		val job = completedJob(userId)
		Mockito.`when`(localUserService.localUserId()).thenReturn(userId)
		Mockito.`when`(store.findForUser(job.id(), userId)).thenReturn(Optional.of(job))
		val mockMvc = standaloneSetup(JobController(store, localUserService)).build()

		mockMvc.perform(get("/api/jobs/{jobId}", job.id()))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.jobId").value(job.id().toString()))
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.result.overallScore").value(84))
			.andExpect(jsonPath("$.completedAt").exists())
	}

	private fun accepted(type: JobType): JobAcceptedResponse {
		val id = UUID.randomUUID()
		return JobAcceptedResponse(id, type, JobStatus.QUEUED, JobStage.QUEUED, "/api/jobs/$id", false)
	}

	private fun completedJob(userId: UUID): BackgroundJob {
		val now = Instant.now()
		return BackgroundJob(UUID.randomUUID(), userId, JobType.ANALYSIS, "resume", null, JobStatus.SUCCEEDED, JobStage.COMPLETED, ObjectMapper().createObjectNode(), ObjectMapper().createObjectNode().put("overallScore", 84), "fingerprint", 1, 3, null, null, false, now, now, now, now, now, now, null, null)
	}
}
