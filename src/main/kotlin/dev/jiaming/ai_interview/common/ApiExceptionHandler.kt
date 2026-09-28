package dev.jiaming.ai_interview.common

import dev.jiaming.ai_interview.gemini.GeminiException
import dev.jiaming.ai_interview.resume.ResumeExtractionException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.server.ResponseStatusException

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ResumeExtractionException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun handleResumeExtractionException(exception: ResumeExtractionException) =
        ApiErrorResponse("RESUME_EXTRACTION_FAILED", exception.message)

    @ExceptionHandler(GeminiException::class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    fun handleGeminiException(exception: GeminiException) =
        ApiErrorResponse(exception.code(), exception.message)

    @ExceptionHandler(ApiRequestException::class)
    fun handleApiRequestException(exception: ApiRequestException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(exception.status()).body(ApiErrorResponse(exception.code(), exception.message))

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(exception: ResponseStatusException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(exception.statusCode).body(
            ApiErrorResponse(codeFor(exception.statusCode.value()), exception.reason ?: "Request failed")
        )

    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MethodArgumentNotValidException::class,
        MethodArgumentTypeMismatchException::class
    )
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleInvalidRequest(@Suppress("UNUSED_PARAMETER") exception: Exception) =
        ApiErrorResponse("INVALID_REQUEST", "The request payload is invalid")

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    fun handleMaxUploadSize(@Suppress("UNUSED_PARAMETER") exception: MaxUploadSizeExceededException) =
        ApiErrorResponse("UPLOAD_TOO_LARGE", "The uploaded file exceeds the configured size limit")

    @ExceptionHandler(Exception::class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    fun handleUnexpectedException(exception: Exception): ApiErrorResponse {
        log.error("api_request_failed exceptionType={}", exception.javaClass.name, exception)
        return ApiErrorResponse("INTERNAL_ERROR", "The request could not be completed")
    }

    private fun codeFor(status: Int): String = when (status) {
        400 -> "INVALID_REQUEST"
        404 -> "NOT_FOUND"
        409 -> "CONFLICT"
        413 -> "UPLOAD_TOO_LARGE"
        422 -> "UNPROCESSABLE_CONTENT"
        429 -> "RATE_LIMITED"
        503 -> "SERVICE_UNAVAILABLE"
        else -> "REQUEST_FAILED"
    }

    private companion object {
        val log = LoggerFactory.getLogger(ApiExceptionHandler::class.java)
    }
}
