package dev.jiaming.ai_interview.rag

import dev.jiaming.ai_interview.common.RuntimeModeProperties
import java.time.Duration
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito

class RagIndexCleanupSchedulerTests {
	private val indexingService = Mockito.mock(RagIndexingService::class.java)

	@Test
	fun runsCleanupWhenTheWorkerIsEnabled() {
		RagIndexCleanupScheduler(indexingService, RuntimeModeProperties("worker")).cleanup()
		Mockito.verify(indexingService).cleanupStale(RETENTION, 100)
	}

	@Test
	fun runsCleanupInCombinedMode() {
		RagIndexCleanupScheduler(indexingService, RuntimeModeProperties("all")).cleanup()
		Mockito.verify(indexingService).cleanupStale(RETENTION, 100)
	}

	@Test
	fun skipsCleanupInApiOnlyMode() {
		RagIndexCleanupScheduler(indexingService, RuntimeModeProperties("api")).cleanup()
		Mockito.verify(indexingService, Mockito.never()).cleanupStale(any(), anyInt())
	}

	private companion object {
		val RETENTION: Duration = Duration.ofDays(7)
	}
}
