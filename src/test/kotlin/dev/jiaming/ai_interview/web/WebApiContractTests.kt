package dev.jiaming.ai_interview.web

import dev.jiaming.ai_interview.common.ApiExceptionHandler
import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.ApiStatusController
import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.jobs.JobController
import dev.jiaming.ai_interview.jobs.JobStatusReader
import java.util.UUID
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.http.HttpStatus
import org.springframework.mock.env.MockEnvironment
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup

class WebApiContractTests {
	@Test
	fun unknownJobReturnsNotFoundContract() {
		val userId = UUID.randomUUID()
		val jobId = UUID.randomUUID()
		val reader = Mockito.mock(JobStatusReader::class.java)
		val localUserService = Mockito.mock(LocalUserService::class.java)
		Mockito.`when`(localUserService.localUserId()).thenReturn(userId)
		Mockito.`when`(reader.findForUser(jobId, userId)).thenReturn(null)
		val mockMvc = standaloneSetup(JobController(reader, localUserService)).setControllerAdvice(ApiExceptionHandler()).build()
		mockMvc.perform(get("/api/jobs/{jobId}", jobId))
			.andExpect(status().isNotFound)
			.andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"))
			.andExpect(jsonPath("$.message").exists())
	}

	@Test
	fun statusReaderFailureUsesServiceUnavailableContract() {
		val userId = UUID.randomUUID()
		val jobId = UUID.randomUUID()
		val reader = Mockito.mock(JobStatusReader::class.java)
		val localUserService = Mockito.mock(LocalUserService::class.java)
		Mockito.`when`(localUserService.localUserId()).thenReturn(userId)
		Mockito.`when`(reader.findForUser(jobId, userId)).thenThrow(
			ApiRequestException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Job status is temporarily unavailable")
		)
		val mockMvc = standaloneSetup(JobController(reader, localUserService)).setControllerAdvice(ApiExceptionHandler()).build()
		mockMvc.perform(get("/api/jobs/{jobId}", jobId))
			.andExpect(status().isServiceUnavailable)
			.andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
			.andExpect(jsonPath("$.message").value("Job status is temporarily unavailable"))
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
}
