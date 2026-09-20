package dev.jiaming.ai_interview.gemini;

public class GeminiException extends RuntimeException {

	private final String code;

	private final Integer statusCode;

	private final boolean retryable;

	public GeminiException(String message) {
		this(GeminiErrorCode.UPSTREAM_ERROR, message, null, null, true);
	}

	public GeminiException(String message, Throwable cause) {
		this(GeminiErrorCode.UPSTREAM_ERROR, message, cause, null, true);
	}

	public GeminiException(String message, int statusCode, boolean retryable) {
		this(codeForStatus(statusCode), message, null, statusCode, retryable);
	}

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

	private static String codeForStatus(int statusCode) {
		return statusCode == 429 ? GeminiErrorCode.RATE_LIMITED : GeminiErrorCode.UPSTREAM_ERROR;
	}
}
