package dev.jiaming.ai_interview.jobs;

public record JobErrorResponse(
	String code,
	String message,
	Boolean retryable
) {
}
