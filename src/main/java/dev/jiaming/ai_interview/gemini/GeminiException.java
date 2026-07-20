package dev.jiaming.ai_interview.gemini;

public class GeminiException extends RuntimeException {

	private final Integer statusCode;

	private final boolean retryable;

	public GeminiException(String message) {
		this(message, null, null, true);
	}

	public GeminiException(String message, Throwable cause) {
		this(message, cause, null, true);
	}

	public GeminiException(String message, int statusCode, boolean retryable) {
		this(message, null, statusCode, retryable);
	}

	private GeminiException(String message, Throwable cause, Integer statusCode, boolean retryable) {
		super(message, cause);
		this.statusCode = statusCode;
		this.retryable = retryable;
	}

	public Integer statusCode() {
		return statusCode;
	}

	public boolean retryable() {
		return retryable;
	}
}
