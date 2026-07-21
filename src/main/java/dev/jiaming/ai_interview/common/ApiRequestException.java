package dev.jiaming.ai_interview.common;

import org.springframework.http.HttpStatusCode;

public class ApiRequestException extends RuntimeException {

	private final HttpStatusCode status;

	private final String code;

	public ApiRequestException(HttpStatusCode status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public HttpStatusCode status() {
		return status;
	}

	public String code() {
		return code;
	}
}
