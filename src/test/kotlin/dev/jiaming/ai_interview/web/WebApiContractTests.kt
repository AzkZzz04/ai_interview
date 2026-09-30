package dev.jiaming.ai_interview.web

import dev.jiaming.ai_interview.coach.AiAnalysisRequest
import dev.jiaming.ai_interview.coach.AnswerFeedbackRequest
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
import org.mockito.kotlin.any
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
	fun interviewFeedbackBindsExpectedSignalsAndReturnsAcceptedJob() {
		Mockito.`when`(submissionService.submitFeedback(any())).thenReturn(accepted(JobType.ANSWER_FEEDBACK, null))
		val mockMvc = standaloneSetup(InterviewController(submissionService)).build()
		mockMvc.perform(post("/api/interview/feedback").contentType(MediaType.APPLICATION_JSON).content(FEEDBACK_BODY))
			.andExpect(status().isAccepted)
			.andExpect(jsonPath("$.jobType").value("ANSWER_FEEDBACK"))

		Mockito.verify(submissionService).submitFeedback(
			AnswerFeedbackRequest(null, "Java", null, "Spring", "Backend Engineer", "Mid-level", "How did you improve reliability?", "ownership", listOf("clear reasoning", "specific example"), "I added retries.")
		)
	}

	@Test
	fun interviewFeedbackRejectsNullExpectedSignal() {
		// Enforced by jackson-module-kotlin 3.x StrictNullChecks (on by default): List<String> items are non-null.
		val mockMvc = standaloneSetup(InterviewController(submissionService)).setControllerAdvice(ApiExceptionHandler()).build()
		mockMvc.perform(post("/api/interview/feedback").contentType(MediaType.APPLICATION_JSON)
			.content(FEEDBACK_BODY.replace("\"specific example\"", "null")))
			.andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))

		Mockito.verifyNoInteractions(submissionService)
	}

	@Test
	fun documentReferencesBindForAnalysisAndFeedback() {
		val resumeId = UUID.randomUUID()
		val jobDescriptionId = UUID.randomUUID()
		val refs = """"resumeId":"$resumeId","jobDescriptionId":"$jobDescriptionId","targetRole":"Backend Engineer","seniority":"Mid-level""""
		Mockito.`when`(submissionService.submitAnalysis(any())).thenReturn(accepted(JobType.ANALYSIS, resumeId))
		Mockito.`when`(submissionService.submitFeedback(any())).thenReturn(accepted(JobType.ANSWER_FEEDBACK, resumeId))
		val mockMvc = standaloneSetup(AssessmentController(submissionService), InterviewController(submissionService)).build()

		mockMvc.perform(post("/api/assessments").contentType(MediaType.APPLICATION_JSON).content("{$refs}"))
			.andExpect(status().isAccepted)
		mockMvc.perform(post("/api/interview/feedback").contentType(MediaType.APPLICATION_JSON)
			.content("""{$refs,"questionText":"Q","category":"c","expectedSignals":["s"],"answerText":"A"}"""))
			.andExpect(status().isAccepted)

		Mockito.verify(submissionService).submitAnalysis(
			AiAnalysisRequest(resumeId, null, jobDescriptionId, null, "Backend Engineer", "Mid-level")
		)
		Mockito.verify(submissionService).submitFeedback(
			AnswerFeedbackRequest(resumeId, null, jobDescriptionId, null, "Backend Engineer", "Mid-level", "Q", "c", listOf("s"), "A")
		)
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
		return JobAcceptedResponse(id, type, JobStatus.QUEUED, JobStage.QUEUED, "/api/jobs/$id", false, JobInputRefs(resumeId, null, null, null))
	}

	private companion object {
		val ANALYSIS_BODY = """{"resumeId":null,"resumeText":"Java","jobDescription":"Spring","targetRole":"Backend Engineer","seniority":"Mid-level"}"""
		val FEEDBACK_BODY = """{"resumeText":"Java","jobDescription":"Spring","targetRole":"Backend Engineer","seniority":"Mid-level","questionText":"How did you improve reliability?","category":"ownership","expectedSignals":["clear reasoning","specific example"],"answerText":"I added retries."}"""
	}
}
