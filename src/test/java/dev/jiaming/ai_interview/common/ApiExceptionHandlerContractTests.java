package dev.jiaming.ai_interview.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import dev.jiaming.ai_interview.gemini.GeminiErrorCode;
import dev.jiaming.ai_interview.gemini.GeminiException;
import dev.jiaming.ai_interview.resume.ResumeExtractionException;

/**
 * Locks the error envelope contract the frontend depends on: every failure surfaces as
 * {@code {code, message}} with a stable HTTP status and a stable {@code code} value. The
 * frontend maps errors by {@code code}, so a rename here must break a test here.
 */
class ApiExceptionHandlerContractTests {

	private final MockMvc mockMvc = standaloneSetup(new ThrowingController())
		.setControllerAdvice(new ApiExceptionHandler())
		.build();

	@Test
	void apiRequestConflictKeepsStatusAndCode() throws Exception {
		perform("api-conflict")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("REFERENCE_MISMATCH"))
			.andExpect(jsonPath("$.message").value("Documents do not match"));
	}

	@Test
	void apiRequestNotFoundKeepsStatusAndCode() throws Exception {
		perform("api-not-found")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESUME_NOT_FOUND"));
	}

	@Test
	void rateLimitStatusMapsToFixedCode() throws Exception {
		perform("rate-limited")
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("RATE_LIMITED"));
	}

	@Test
	void serviceUnavailableMapsToFixedCode() throws Exception {
		perform("unavailable")
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
	}

	@Test
	void badRequestStatusMapsToInvalidRequest() throws Exception {
		perform("bad-request")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	void geminiFailureBecomesBadGatewayWithProviderCode() throws Exception {
		perform("gemini")
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.code").value(GeminiErrorCode.SAFETY));
	}

	@Test
	void resumeExtractionFailureIsUnprocessable() throws Exception {
		perform("extraction")
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("RESUME_EXTRACTION_FAILED"));
	}

	@Test
	void oversizedUploadIsPayloadTooLarge() throws Exception {
		perform("too-large")
			.andExpect(status().isPayloadTooLarge())
			.andExpect(jsonPath("$.code").value("UPLOAD_TOO_LARGE"));
	}

	@Test
	void malformedJsonBodyIsInvalidRequest() throws Exception {
		mockMvc.perform(post("/throw/body").contentType(MediaType.APPLICATION_JSON).content("{ not json"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	private org.springframework.test.web.servlet.ResultActions perform(String type) throws Exception {
		return mockMvc.perform(get("/throw").param("type", type));
	}

	@RestController
	static class ThrowingController {

		@GetMapping("/throw")
		String throwByType(@RequestParam String type) {
			switch (type) {
				case "api-conflict" -> throw new ApiRequestException(
					HttpStatus.CONFLICT, "REFERENCE_MISMATCH", "Documents do not match");
				case "api-not-found" -> throw new ApiRequestException(
					HttpStatus.NOT_FOUND, "RESUME_NOT_FOUND", "Resume was not found");
				case "rate-limited" -> throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "slow down");
				case "unavailable" -> throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "worker only");
				case "bad-request" -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad");
				case "gemini" -> throw new GeminiException(GeminiErrorCode.SAFETY, "blocked", false);
				case "extraction" -> throw new ResumeExtractionException("Encrypted PDF");
				case "too-large" -> throw new MaxUploadSizeExceededException(10);
				default -> throw new IllegalStateException("unexpected");
			}
		}

		@PostMapping("/throw/body")
		String body(@RequestBody Payload payload) {
			return payload.value();
		}
	}

	record Payload(String value) {
	}
}
