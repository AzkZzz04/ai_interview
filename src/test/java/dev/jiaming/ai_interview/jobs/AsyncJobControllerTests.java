package dev.jiaming.ai_interview.jobs;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import dev.jiaming.ai_interview.common.LocalUserService;
import dev.jiaming.ai_interview.interview.InterviewController;

class AsyncJobControllerTests {

	private final JobSubmissionService submissionService = mock(JobSubmissionService.class);

	@Test
	void analysisSubmissionReturnsAcceptedJob() throws Exception {
		JobAcceptedResponse accepted = accepted(JobType.ANALYSIS);
		when(submissionService.submitAnalysis(any())).thenReturn(accepted);
		MockMvc mockMvc = standaloneSetup(new AnalysisController(submissionService)).build();

		mockMvc.perform(post("/api/analyses")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"resumeText":"Java","jobDescription":"Spring","targetRole":"Backend Engineer","seniority":"Mid-level"}
					"""))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.jobType").value("ANALYSIS"))
			.andExpect(jsonPath("$.status").value("QUEUED"));
	}

	@Test
	void feedbackSubmissionReturnsAcceptedJob() throws Exception {
		JobAcceptedResponse accepted = accepted(JobType.ANSWER_FEEDBACK);
		when(submissionService.submitFeedback(any())).thenReturn(accepted);
		MockMvc mockMvc = standaloneSetup(new InterviewController(submissionService)).build();

		mockMvc.perform(post("/api/interview/feedback")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "resumeText":"Java","jobDescription":"Spring","targetRole":"Backend Engineer",
					  "seniority":"Mid-level","questionText":"Explain a service","category":"Technical",
					  "expectedSignals":["trade-offs"],"answerText":"I built it with Spring Boot."
					}
					"""))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.jobType").value("ANSWER_FEEDBACK"));
	}

	@Test
	void jobStatusReturnsResultAndTimestamps() throws Exception {
		UUID userId = UUID.randomUUID();
		BackgroundJobStore store = mock(BackgroundJobStore.class);
		LocalUserService localUserService = mock(LocalUserService.class);
		BackgroundJob job = completedJob(userId);
		when(localUserService.localUserId()).thenReturn(userId);
		when(store.findForUser(job.id(), userId)).thenReturn(Optional.of(job));
		MockMvc mockMvc = standaloneSetup(new JobController(store, localUserService)).build();

		mockMvc.perform(get("/api/jobs/{jobId}", job.id()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.jobId").value(job.id().toString()))
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.result.overallScore").value(84))
			.andExpect(jsonPath("$.completedAt").exists());
	}

	private JobAcceptedResponse accepted(JobType type) {
		UUID id = UUID.randomUUID();
		return new JobAcceptedResponse(id, type, JobStatus.QUEUED, JobStage.QUEUED, "/api/jobs/" + id, false);
	}

	private BackgroundJob completedJob(UUID userId) {
		Instant now = Instant.now();
		return new BackgroundJob(
			UUID.randomUUID(), userId, JobType.ANALYSIS, "resume", null, JobStatus.SUCCEEDED,
			JobStage.COMPLETED, new ObjectMapper().createObjectNode(),
			new ObjectMapper().createObjectNode().put("overallScore", 84), "fingerprint", 1, 3,
			null, null, false, now, now, now, now, now, now, null, null
		);
	}
}
