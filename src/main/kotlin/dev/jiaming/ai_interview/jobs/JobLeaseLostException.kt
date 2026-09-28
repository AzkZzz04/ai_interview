package dev.jiaming.ai_interview.jobs

import java.util.UUID

class JobLeaseLostException(jobId: UUID) : RuntimeException("Worker no longer owns the lease for job $jobId")
