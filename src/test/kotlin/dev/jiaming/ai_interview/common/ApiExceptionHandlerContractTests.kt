package dev.jiaming.ai_interview.common

import dev.jiaming.ai_interview.gemini.GeminiErrorCode
import dev.jiaming.ai_interview.gemini.GeminiException
import dev.jiaming.ai_interview.resume.ResumeExtractionException
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.server.ResponseStatusException

class ApiExceptionHandlerContractTests {

    private val mockMvc: MockMvc = standaloneSetup(ThrowingController())
        .setControllerAdvice(ApiExceptionHandler())
        .build()

    @Test
    fun apiRequestConflictKeepsStatusAndCode() {
        perform("api-conflict")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("REFERENCE_MISMATCH"))
            .andExpect(jsonPath("$.message").value("Documents do not match"))
    }

    @Test
    fun apiRequestNotFoundKeepsStatusAndCode() {
        perform("api-not-found")
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESUME_NOT_FOUND"))
    }

    @Test
    fun rateLimitStatusMapsToFixedCode() {
        perform("rate-limited")
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
    }

    @Test
    fun serviceUnavailableMapsToFixedCode() {
        perform("unavailable")
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
    }

    @Test
    fun badRequestStatusMapsToInvalidRequest() {
        perform("bad-request")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
    }

    @Test
    fun geminiFailureBecomesBadGatewayWithProviderCode() {
        perform("gemini")
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.code").value(GeminiErrorCode.SAFETY))
    }

    @Test
    fun resumeExtractionFailureIsUnprocessable() {
        perform("extraction")
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("RESUME_EXTRACTION_FAILED"))
    }

    @Test
    fun oversizedUploadIsPayloadTooLarge() {
        perform("too-large")
            .andExpect(status().isPayloadTooLarge)
            .andExpect(jsonPath("$.code").value("UPLOAD_TOO_LARGE"))
    }

    @Test
    fun malformedJsonBodyIsInvalidRequest() {
        mockMvc.perform(post("/throw/body").contentType(MediaType.APPLICATION_JSON).content("{ not json"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
    }

    private fun perform(type: String): ResultActions = mockMvc.perform(get("/throw").param("type", type))

    @RestController
    class ThrowingController {

        @GetMapping("/throw")
        fun throwByType(@RequestParam type: String): String = when (type) {
            "api-conflict" -> throw ApiRequestException(
                HttpStatus.CONFLICT, "REFERENCE_MISMATCH", "Documents do not match"
            )
            "api-not-found" -> throw ApiRequestException(
                HttpStatus.NOT_FOUND, "RESUME_NOT_FOUND", "Resume was not found"
            )
            "rate-limited" -> throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "slow down")
            "unavailable" -> throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "worker only")
            "bad-request" -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "bad")
            "gemini" -> throw GeminiException(GeminiErrorCode.SAFETY, "blocked", false)
            "extraction" -> throw ResumeExtractionException("Encrypted PDF")
            "too-large" -> throw MaxUploadSizeExceededException(10)
            else -> throw IllegalStateException("unexpected")
        }

        @PostMapping("/throw/body")
        fun body(@RequestBody payload: Payload): String = payload.value
    }

    data class Payload(val value: String)
}
