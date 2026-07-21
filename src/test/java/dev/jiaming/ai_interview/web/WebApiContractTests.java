package dev.jiaming.ai_interview.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;

import dev.jiaming.ai_interview.assessment.AssessmentController;
import dev.jiaming.ai_interview.common.ApiExceptionHandler;
import dev.jiaming.ai_interview.common.ApiStatusController;
import dev.jiaming.ai_interview.common.LocalUserService;
import dev.jiaming.ai_interview.interview.InterviewController;
import dev.jiaming.ai_interview.jobs.BackgroundJobStore;
import dev.jiaming.ai_interview.jobs.JobAcceptedResponse;
import dev.jiaming.ai_interview.jobs.JobController;
import dev.jiaming.ai_interview.jobs.JobInputRefs;
import dev.jiaming.ai_interview.jobs.JobStage;
import dev.jiaming.ai_interview.jobs.JobStatus;
import dev.jiaming.ai_interview.jobs.JobSubmissionService;
import dev.jiaming.ai_interview.jobs.JobType;

class WebApiContractTests {

	private static final String ANALYSIS_BODY = """
		{"resumeId":null,"resumeText":"Java","jobDescription":"Spring",
		 "targetRole":"Backend Engineer","seniority":"Mid-level"}
		""";

	private final JobSubmissionService submissionService = mock(JobSubmissionService.class);

	@Test
	void assessmentSubmissionReturnsAcceptedJobWithInputRefs() throws Exception {
		UUID resumeId = UUID.randomUUID();
		when(submissionService.submitAnalysis(any())).thenReturn(accepted(JobType.ANALYSIS, resumeId));
		MockMvc mockMvc = standaloneSetup(new AssessmentController(submissionService)).build();

		mockMvc.perform(post("/api/assessments").contentType(MediaType.APPLICATION_JSON).content(ANALYSIS_BODY))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.jobType").value("ANALYSIS"))
			.andExpect(jsonPath("$.status").value("QUEUED"))
			.andExpect(jsonPath("$.inputRefs.resumeId").value(resumeId.toString()));
	}

	@Test
	void interviewQuestionsSubmissionReturnsAcceptedJob() throws Exception {
		when(submissionService.submitAnalysis(any())).thenReturn(accepted(JobType.ANALYSIS, null));
		MockMvc mockMvc = standaloneSetup(new InterviewController(submissionService)).build();

		mockMvc.perform(post("/api/interview/questions").contentType(MediaType.APPLICATION_JSON).content(ANALYSIS_BODY))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.jobType").value("ANALYSIS"));
	}

	@Test
	void unknownJobReturnsNotFoundContract() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		BackgroundJobStore store = mock(BackgroundJobStore.class);
		LocalUserService localUserService = mock(LocalUserService.class);
		when(localUserService.localUserId()).thenReturn(userId);
		when(store.findForUser(jobId, userId)).thenReturn(Optional.empty());
		MockMvc mockMvc = standaloneSetup(new JobController(store, localUserService))
			.setControllerAdvice(new ApiExceptionHandler())
			.build();

		mockMvc.perform(get("/api/jobs/{jobId}", jobId))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"))
			.andExpect(jsonPath("$.message").exists());
	}

	@Test
	void statusEndpointReportsService() throws Exception {
		MockEnvironment environment = new MockEnvironment();
		environment.setProperty("spring.application.name", "ai_interview");
		@SuppressWarnings("unchecked")
		ObjectProvider<BuildProperties> buildProperties = mock(ObjectProvider.class);
		when(buildProperties.getIfAvailable()).thenReturn(null);
		MockMvc mockMvc = standaloneSetup(new ApiStatusController(environment, buildProperties)).build();

		mockMvc.perform(get("/api/status"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.service").value("ai_interview"))
			.andExpect(jsonPath("$.build").value("dev"))
			.andExpect(jsonPath("$.timestamp").exists());
	}

	private JobAcceptedResponse accepted(JobType type, UUID resumeId) {
		UUID id = UUID.randomUUID();
		return new JobAcceptedResponse(
			id, type, JobStatus.QUEUED, JobStage.QUEUED, "/api/jobs/" + id, false,
			new JobInputRefs(resumeId, null)
		);
	}
}
