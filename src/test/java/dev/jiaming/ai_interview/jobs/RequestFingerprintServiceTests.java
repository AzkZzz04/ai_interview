package dev.jiaming.ai_interview.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import dev.jiaming.ai_interview.coach.AiAnalysisRequest;

class RequestFingerprintServiceTests {

	private final RequestFingerprintService service = new RequestFingerprintService(new ObjectMapper());

	@Test
	void producesStableFingerprintForSameRequest() {
		AiAnalysisRequest request = new AiAnalysisRequest("resume", "job", "Backend Engineer", "Mid-level");

		assertThat(service.fingerprint("analysis", request))
			.isEqualTo(service.fingerprint("analysis", request))
			.hasSize(64);
	}

	@Test
	void changesFingerprintWhenRequestInputsChange() {
		AiAnalysisRequest first = new AiAnalysisRequest("resume", "job", "Backend Engineer", "Mid-level");
		AiAnalysisRequest second = new AiAnalysisRequest("resume", "job", "Backend Engineer", "Senior");

		assertThat(service.fingerprint("analysis", first))
			.isNotEqualTo(service.fingerprint("analysis", second));
	}
}
