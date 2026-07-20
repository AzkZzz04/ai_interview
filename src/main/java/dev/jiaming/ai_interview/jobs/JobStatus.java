package dev.jiaming.ai_interview.jobs;

public enum JobStatus {

	QUEUED,
	PROCESSING,
	RETRYING,
	SUCCEEDED,
	PARTIAL,
	FAILED;

	public boolean terminal() {
		return this == SUCCEEDED || this == PARTIAL || this == FAILED;
	}
}
