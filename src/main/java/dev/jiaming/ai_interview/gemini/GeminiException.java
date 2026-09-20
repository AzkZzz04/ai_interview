package dev.jiaming.ai_interview.gemini;

public class GeminiException extends RuntimeException {

	private final String code;

	private final Integer statusCode;

	private final boolean retryable;

	public GeminiException(String code, String message, boolean retryable) {
		this(code, message, null, null, retryable);
	}

	public GeminiException(String code, String message, Throwable cause, boolean retryable) {
		this(code, message, cause, null, retryable);
	}

	public GeminiException(String code, String message, Integer statusCode, boolean retryable) {
		this(code, message, null, statusCode, retryable);
	}

	private GeminiException(String code, String message, Throwable cause, Integer statusCode, boolean retryable) {
		super(message, cause);
		this.code = code;
		this.statusCode = statusCode;
		this.retryable = retryable;
	}

	public String code() {
		return code;
	}

	public Integer statusCode() {
		return statusCode;
	}

	public boolean retryable() {
		return retryable;
	}
}
