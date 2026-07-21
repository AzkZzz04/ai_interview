package dev.jiaming.ai_interview.rag;

import java.time.Duration;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.jiaming.ai_interview.common.RuntimeModeProperties;

@Component
public class RagIndexCleanupScheduler {

	private static final Duration RETENTION = Duration.ofDays(7);

	private final RagIndexingService indexingService;

	private final RuntimeModeProperties runtimeMode;

	public RagIndexCleanupScheduler(RagIndexingService indexingService, RuntimeModeProperties runtimeMode) {
		this.indexingService = indexingService;
		this.runtimeMode = runtimeMode;
	}

	@Scheduled(fixedDelayString = "${app.rag.cleanup-interval-ms:3600000}")
	public void cleanup() {
		if (runtimeMode.workerEnabled()) {
			indexingService.cleanupStale(RETENTION, 100);
		}
	}
}
