package dev.jiaming.ai_interview.rag

import dev.jiaming.ai_interview.common.RuntimeModeProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class RagIndexCleanupScheduler(
    private val indexingService: RagIndexingService,
    private val runtimeMode: RuntimeModeProperties
) {
    @Scheduled(fixedDelayString = "\${app.rag.cleanup-interval-ms:3600000}")
    fun cleanup() {
        if (runtimeMode.workerEnabled()) indexingService.cleanupStale(RETENTION, 100)
    }

    companion object { private val RETENTION = Duration.ofDays(7) }
}
