package dev.jiaming.ai_interview.gemini;

public final class GeminiErrorCode {

	public static final String NOT_CONFIGURED = "GEMINI_NOT_CONFIGURED";

	public static final String RATE_LIMITED = "GEMINI_RATE_LIMITED";

	public static final String TIMEOUT = "GEMINI_TIMEOUT";

	public static final String UPSTREAM_ERROR = "GEMINI_UPSTREAM_ERROR";

	public static final String SAFETY = "GEMINI_SAFETY";

	public static final String RECITATION = "GEMINI_RECITATION";

	public static final String MAX_TOKENS = "GEMINI_MAX_TOKENS";

	public static final String EMPTY_RESPONSE = "GEMINI_EMPTY_RESPONSE";

	public static final String INVALID_RESPONSE = "GEMINI_INVALID_RESPONSE";

	private GeminiErrorCode() {
	}
}
