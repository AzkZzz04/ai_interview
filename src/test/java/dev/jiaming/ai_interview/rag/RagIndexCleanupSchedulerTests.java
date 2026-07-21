package dev.jiaming.ai_interview.rag;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import dev.jiaming.ai_interview.common.RuntimeModeProperties;

class RagIndexCleanupSchedulerTests {

	private static final Duration RETENTION = Duration.ofDays(7);

	private final RagIndexingService indexingService = mock(RagIndexingService.class);

	@Test
	void runsCleanupWhenTheWorkerIsEnabled() {
		RagIndexCleanupScheduler scheduler = new RagIndexCleanupScheduler(
			indexingService, new RuntimeModeProperties("worker")
		);

		scheduler.cleanup();

		verify(indexingService).cleanupStale(RETENTION, 100);
	}

	@Test
	void runsCleanupInCombinedMode() {
		RagIndexCleanupScheduler scheduler = new RagIndexCleanupScheduler(
			indexingService, new RuntimeModeProperties("all")
		);

		scheduler.cleanup();

		verify(indexingService).cleanupStale(RETENTION, 100);
	}

	@Test
	void skipsCleanupInApiOnlyMode() {
		RagIndexCleanupScheduler scheduler = new RagIndexCleanupScheduler(
			indexingService, new RuntimeModeProperties("api")
		);

		scheduler.cleanup();

		verify(indexingService, never()).cleanupStale(any(), anyInt());
	}
}
