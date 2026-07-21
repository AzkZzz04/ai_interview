package dev.jiaming.ai_interview.jobs;

import java.util.UUID;

public record JobAcceptedResponse(
	UUID jobId,
	JobType jobType,
	JobStatus status,
	JobStage stage,
	String statusUrl,
	boolean reused,
	JobInputRefs inputRefs
) {
	public JobAcceptedResponse(
		UUID jobId,
		JobType jobType,
		JobStatus status,
		JobStage stage,
		String statusUrl,
		boolean reused
	) {
		this(jobId, jobType, status, stage, statusUrl, reused, new JobInputRefs(null, null));
	}

	public static JobAcceptedResponse from(BackgroundJob job, boolean reused) {
		return new JobAcceptedResponse(
			job.id(),
			job.jobType(),
			job.status(),
			job.stage(),
			"/api/jobs/" + job.id(),
			reused,
			JobInputRefs.from(job)
		);
	}
}
