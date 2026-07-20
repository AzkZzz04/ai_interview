package dev.jiaming.ai_interview.jobs;

public interface JobTerminalFailureHandler {

	boolean supports(JobType jobType);

	void handle(BackgroundJob job, String errorCode, String errorMessage);
}
