package dev.jiaming.ai_interview.resume;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import dev.jiaming.ai_interview.jobs.JobAcceptedResponse;
import dev.jiaming.ai_interview.jobs.JobStage;
import dev.jiaming.ai_interview.jobs.JobStatus;
import dev.jiaming.ai_interview.jobs.JobType;

class ResumeControllerTests {

	private final ResumeUploadService service = mock(ResumeUploadService.class);

	private final ResumeJobSubmissionService submissionService = mock(ResumeJobSubmissionService.class);

	private final MockMvc mockMvc = standaloneSetup(new ResumeController(service, submissionService)).build();

	@Test
	void uploadsResumeAndReturnsAcceptedJob() throws Exception {
		UUID jobId = UUID.randomUUID();
		when(submissionService.submit(any())).thenReturn(new JobAcceptedResponse(
			jobId,
			JobType.RESUME_EXTRACTION,
			JobStatus.QUEUED,
			JobStage.QUEUED,
			"/api/jobs/" + jobId,
			false
		));
		MockMultipartFile file = new MockMultipartFile(
			"file",
			"resume.txt",
			"text/plain",
			"SKILLS\nJava Spring Boot".getBytes(StandardCharsets.UTF_8)
		);

		mockMvc.perform(multipart("/api/resumes").file(file))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.jobId").value(jobId.toString()))
			.andExpect(jsonPath("$.jobType").value("RESUME_EXTRACTION"))
			.andExpect(jsonPath("$.status").value("QUEUED"));
	}

	@Test
	void returnsCurrentCompletedResume() throws Exception {
		when(service.current()).thenReturn(Optional.of(new ResumeUploadResponse(
			UUID.randomUUID().toString(),
			"resume.txt",
			"text/plain",
			"text/plain",
			24,
			24,
			24,
			"SKILLS\nJava Spring Boot",
			List.of(new ResumeChunkResponse(0, "Skills", "Java Spring Boot", 16)),
			Instant.now()
		)));
		mockMvc.perform(get("/api/resumes/current"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.originalFilename").value("resume.txt"));
	}

	@Test
	void returnsNotFoundWhenNoResumeHasBeenUploaded() throws Exception {
		ResumeUploadService emptyService = mock(ResumeUploadService.class);
		when(emptyService.current()).thenReturn(Optional.empty());
		MockMvc emptyMockMvc = standaloneSetup(new ResumeController(emptyService, submissionService)).build();

		emptyMockMvc.perform(get("/api/resumes/current"))
			.andExpect(status().isNotFound());
	}
}
