package dev.jiaming.ai_interview.jobs;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import dev.jiaming.ai_interview.common.LocalUserService;

@RestController
@RequestMapping("/api/jobs")
@CrossOrigin(origins = { "http://localhost:3000", "http://127.0.0.1:3000" })
public class JobController {

	private final BackgroundJobStore jobStore;

	private final LocalUserService localUserService;

	public JobController(BackgroundJobStore jobStore, LocalUserService localUserService) {
		this.jobStore = jobStore;
		this.localUserService = localUserService;
	}

	@GetMapping("/{jobId}")
	public JobStatusResponse status(@PathVariable UUID jobId) {
		return jobStore.findForUser(jobId, localUserService.localUserId())
			.map(JobStatusResponse::from)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Background job was not found"));
	}
}
