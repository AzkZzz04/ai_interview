package dev.jiaming.ai_interview.jobs;

import java.util.UUID;

public record JobAcceptedResponse(
	UUID jobId,
	JobType jobType,
	JobStatus status,
	JobStage stage,
	String statusUrl,
	boolean reused
) {

	public static JobAcceptedResponse from(BackgroundJob job, boolean reused) {
		return new JobAcceptedResponse(
			job.id(),
			job.jobType(),
			job.status(),
			job.stage(),
			"/api/jobs/" + job.id(),
			reused
		);
	}
}
