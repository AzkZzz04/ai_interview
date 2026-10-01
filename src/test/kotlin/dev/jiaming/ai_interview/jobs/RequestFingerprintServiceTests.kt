package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RequestFingerprintServiceTests {
	private val service = RequestFingerprintService(ObjectMapper())

	@Test
	fun producesStableFingerprintForSameRequest() {
		val request = JobFitPayload(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
		assertThat(service.fingerprint("JOB_FIT", request))
			.isEqualTo(service.fingerprint("JOB_FIT", request))
			.hasSize(64)
	}

	@Test
	fun changesFingerprintWhenRequestInputsChange() {
		val resumeId = UUID.randomUUID()
		val first = JobFitPayload(UUID.randomUUID(), resumeId, UUID.randomUUID())
		val second = first.copy(targetJobId = UUID.randomUUID())
		assertThat(service.fingerprint("JOB_FIT", first))
			.isNotEqualTo(service.fingerprint("JOB_FIT", second))
	}
}
