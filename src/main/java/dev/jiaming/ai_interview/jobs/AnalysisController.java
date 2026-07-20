package dev.jiaming.ai_interview.jobs;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.jiaming.ai_interview.coach.AiAnalysisRequest;

@RestController
@RequestMapping("/api/analyses")
@CrossOrigin(origins = { "http://localhost:3000", "http://127.0.0.1:3000" })
public class AnalysisController {

	private final JobSubmissionService jobSubmissionService;

	public AnalysisController(JobSubmissionService jobSubmissionService) {
		this.jobSubmissionService = jobSubmissionService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	public JobAcceptedResponse submit(@RequestBody AiAnalysisRequest request) {
		return jobSubmissionService.submitAnalysis(request);
	}
}
