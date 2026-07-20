package dev.jiaming.ai_interview.assessment;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

import dev.jiaming.ai_interview.coach.AiAnalysisRequest;
import dev.jiaming.ai_interview.jobs.JobAcceptedResponse;
import dev.jiaming.ai_interview.jobs.JobSubmissionService;

@RestController
@RequestMapping("/api/assessments")
@CrossOrigin(origins = { "http://localhost:3000", "http://127.0.0.1:3000" })
public class AssessmentController {

	private final JobSubmissionService jobSubmissionService;

	public AssessmentController(JobSubmissionService jobSubmissionService) {
		this.jobSubmissionService = jobSubmissionService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	public JobAcceptedResponse assess(@RequestBody AiAnalysisRequest request) {
		return jobSubmissionService.submitAnalysis(request);
	}
}
