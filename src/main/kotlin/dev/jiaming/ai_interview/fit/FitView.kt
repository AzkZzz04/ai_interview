package dev.jiaming.ai_interview.fit

import dev.jiaming.ai_interview.jobs.ActiveJob
import java.time.Instant
import java.util.UUID

data class FitView(
    val resumeId: UUID,
    val targetJobId: UUID,
    val result: JobFitResult?,
    val createdAt: Instant?,
    val activeJob: ActiveJob?,
)
