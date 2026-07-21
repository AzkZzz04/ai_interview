package dev.jiaming.ai_interview.common;

import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import dev.jiaming.ai_interview.gemini.GeminiException;
import dev.jiaming.ai_interview.resume.ResumeExtractionException;

@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(ResumeExtractionException.class)
	@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
	public ApiErrorResponse handleResumeExtractionException(ResumeExtractionException exception) {
		return new ApiErrorResponse("RESUME_EXTRACTION_FAILED", exception.getMessage());
	}

	@ExceptionHandler(GeminiException.class)
	@ResponseStatus(HttpStatus.BAD_GATEWAY)
	public ApiErrorResponse handleGeminiException(GeminiException exception) {
		return new ApiErrorResponse(exception.code(), exception.getMessage());
	}

	@ExceptionHandler(ApiRequestException.class)
	public ResponseEntity<ApiErrorResponse> handleApiRequestException(ApiRequestException exception) {
		return ResponseEntity.status(exception.status())
			.body(new ApiErrorResponse(exception.code(), exception.getMessage()));
	}

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<ApiErrorResponse> handleResponseStatusException(ResponseStatusException exception) {
		return ResponseEntity.status(exception.getStatusCode())
			.body(new ApiErrorResponse(
				codeFor(exception.getStatusCode().value()),
				exception.getReason() == null ? "Request failed" : exception.getReason()
			));
	}

	@ExceptionHandler({
		HttpMessageNotReadableException.class,
		MethodArgumentNotValidException.class,
		MethodArgumentTypeMismatchException.class
	})
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiErrorResponse handleInvalidRequest(Exception exception) {
		return new ApiErrorResponse("INVALID_REQUEST", "The request payload is invalid");
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	@ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
	public ApiErrorResponse handleMaxUploadSize(MaxUploadSizeExceededException exception) {
		return new ApiErrorResponse("UPLOAD_TOO_LARGE", "The uploaded file exceeds the configured size limit");
	}

	@ExceptionHandler(Exception.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public ApiErrorResponse handleUnexpectedException(Exception exception) {
		log.error("api_request_failed exceptionType={}", exception.getClass().getName(), exception);
		return new ApiErrorResponse("INTERNAL_ERROR", "The request could not be completed");
	}

	private String codeFor(int status) {
		return switch (status) {
			case 400 -> "INVALID_REQUEST";
			case 404 -> "NOT_FOUND";
			case 409 -> "CONFLICT";
			case 413 -> "UPLOAD_TOO_LARGE";
			case 422 -> "UNPROCESSABLE_CONTENT";
			case 429 -> "RATE_LIMITED";
			case 503 -> "SERVICE_UNAVAILABLE";
			default -> "REQUEST_FAILED";
		};
	}
}
