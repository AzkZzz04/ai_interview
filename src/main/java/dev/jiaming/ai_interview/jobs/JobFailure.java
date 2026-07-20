package dev.jiaming.ai_interview.jobs;

record JobFailure(
	String code,
	String message,
	boolean retryable
) {
}
