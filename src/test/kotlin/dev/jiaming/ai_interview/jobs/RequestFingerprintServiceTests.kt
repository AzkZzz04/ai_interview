package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.coach.AiAnalysisRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RequestFingerprintServiceTests {
	private val service = RequestFingerprintService(ObjectMapper())

	@Test
	fun producesStableFingerprintForSameRequest() {
		val request = AiAnalysisRequest("resume", "job", "Backend Engineer", "Mid-level")
		assertThat(service.fingerprint("analysis", request))
			.isEqualTo(service.fingerprint("analysis", request))
			.hasSize(64)
	}

	@Test
	fun changesFingerprintWhenRequestInputsChange() {
		val first = AiAnalysisRequest("resume", "job", "Backend Engineer", "Mid-level")
		val second = AiAnalysisRequest("resume", "job", "Backend Engineer", "Senior")
		assertThat(service.fingerprint("analysis", first))
			.isNotEqualTo(service.fingerprint("analysis", second))
	}
}
