package dev.jiaming.ai_interview.jobs;

import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import dev.jiaming.ai_interview.gemini.GeminiException;
import dev.jiaming.ai_interview.resume.ResumeExtractionException;
import dev.jiaming.ai_interview.resume.ResumeParserBusyException;

@Component
class JobFailureClassifier {

	JobFailure classify(Throwable throwable) {
		Throwable cause = unwrap(throwable);
		if (cause instanceof GeminiException exception) {
			String suffix = exception.statusCode() == null ? "ERROR" : String.valueOf(exception.statusCode());
			return new JobFailure("GEMINI_" + suffix, message(exception), exception.retryable());
		}
		if (cause instanceof ResumeParserBusyException exception) {
			return new JobFailure("RESUME_PARSER_BUSY", message(exception), true);
		}
		if (cause instanceof ResumeExtractionException exception) {
			return new JobFailure("RESUME_EXTRACTION_FAILED", message(exception), false);
		}
		if (cause instanceof ResponseStatusException exception) {
			HttpStatusCode status = exception.getStatusCode();
			boolean retryable = status.value() == 408 || status.value() == 429 || status.is5xxServerError();
			return new JobFailure("HTTP_" + status.value(), message(exception), retryable);
		}
		if (cause instanceof IllegalArgumentException exception) {
			return new JobFailure("INVALID_REQUEST", message(exception), false);
		}
		return new JobFailure("PROCESSING_ERROR", message(cause), true);
	}

	private Throwable unwrap(Throwable throwable) {
		Throwable current = throwable;
		while (current.getCause() != null && current != current.getCause()) {
			if (current instanceof GeminiException
				|| current instanceof ResumeExtractionException
				|| current instanceof ResponseStatusException
				|| current instanceof IllegalArgumentException) {
				return current;
			}
			current = current.getCause();
		}
		return current;
	}

	private String message(Throwable throwable) {
		String message = throwable.getMessage();
		return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
	}
}
