package dev.jiaming.ai_interview.jobs;

import java.util.UUID;

class JobLeaseLostException extends RuntimeException {

	JobLeaseLostException(UUID jobId) {
		super("Worker no longer owns the lease for job " + jobId);
	}
}
