package dev.jiaming.ai_interview.web

import dev.jiaming.ai_interview.assessment.AssessmentController
import dev.jiaming.ai_interview.common.ApiExceptionHandler
import dev.jiaming.ai_interview.common.ApiStatusController
import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.interview.InterviewController
import dev.jiaming.ai_interview.jobs.BackgroundJobStore
import dev.jiaming.ai_interview.jobs.JobAcceptedResponse
import dev.jiaming.ai_interview.jobs.JobController
import dev.jiaming.ai_interview.jobs.JobInputRefs
import dev.jiaming.ai_interview.jobs.JobStage
import dev.jiaming.ai_interview.jobs.JobStatus
import dev.jiaming.ai_interview.jobs.JobSubmissionService
import dev.jiaming.ai_interview.jobs.JobType
import java.util.Optional
import java.util.UUID
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.http.MediaType
import org.springframework.mock.env.MockEnvironment
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup

class WebApiContractTests {
	private val submissionService = Mockito.mock(JobSubmissionService::class.java)

	@Test
	fun assessmentSubmissionReturnsAcceptedJobWithInputRefs() {
		val resumeId = UUID.randomUUID()
		Mockito.`when`(submissionService.submitAnalysis(any())).thenReturn(accepted(JobType.ANALYSIS, resumeId))
		val mockMvc = standaloneSetup(AssessmentController(submissionService)).build()
		mockMvc.perform(post("/api/assessments").contentType(MediaType.APPLICATION_JSON).content(ANALYSIS_BODY))
			.andExpect(status().isAccepted)
			.andExpect(jsonPath("$.jobType").value("ANALYSIS"))
			.andExpect(jsonPath("$.status").value("QUEUED"))
			.andExpect(jsonPath("$.inputRefs.resumeId").value(resumeId.toString()))
	}

	@Test
	fun interviewQuestionsSubmissionReturnsAcceptedJob() {
		Mockito.`when`(submissionService.submitAnalysis(any())).thenReturn(accepted(JobType.ANALYSIS, null))
		val mockMvc = standaloneSetup(InterviewController(submissionService)).build()
		mockMvc.perform(post("/api/interview/questions").contentType(MediaType.APPLICATION_JSON).content(ANALYSIS_BODY))
			.andExpect(status().isAccepted)
			.andExpect(jsonPath("$.jobType").value("ANALYSIS"))
	}

	@Test
	fun unknownJobReturnsNotFoundContract() {
		val userId = UUID.randomUUID()
		val jobId = UUID.randomUUID()
		val store = Mockito.mock(BackgroundJobStore::class.java)
		val localUserService = Mockito.mock(LocalUserService::class.java)
		Mockito.`when`(localUserService.localUserId()).thenReturn(userId)
		Mockito.`when`(store.findForUser(jobId, userId)).thenReturn(Optional.empty())
		val mockMvc = standaloneSetup(JobController(store, localUserService)).setControllerAdvice(ApiExceptionHandler()).build()
		mockMvc.perform(get("/api/jobs/{jobId}", jobId))
			.andExpect(status().isNotFound)
			.andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"))
			.andExpect(jsonPath("$.message").exists())
	}

	@Test
	@Suppress("UNCHECKED_CAST")
	fun statusEndpointReportsService() {
		val environment = MockEnvironment()
		environment.setProperty("spring.application.name", "ai_interview")
		val buildProperties = Mockito.mock(ObjectProvider::class.java) as ObjectProvider<BuildProperties>
		Mockito.`when`(buildProperties.ifAvailable).thenReturn(null)
		val mockMvc = standaloneSetup(ApiStatusController(environment, buildProperties)).build()
		mockMvc.perform(get("/api/status"))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.service").value("ai_interview"))
			.andExpect(jsonPath("$.build").value("dev"))
			.andExpect(jsonPath("$.timestamp").exists())
	}

	private fun accepted(type: JobType, resumeId: UUID?): JobAcceptedResponse {
		val id = UUID.randomUUID()
		return JobAcceptedResponse(id, type, JobStatus.QUEUED, JobStage.QUEUED, "/api/jobs/$id", false, JobInputRefs(resumeId, null))
	}

	private companion object {
		val ANALYSIS_BODY = """{"resumeId":null,"resumeText":"Java","jobDescription":"Spring","targetRole":"Backend Engineer","seniority":"Mid-level"}"""
	}
}
