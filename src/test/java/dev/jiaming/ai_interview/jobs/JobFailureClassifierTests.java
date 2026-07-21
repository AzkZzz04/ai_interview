package dev.jiaming.ai_interview.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import dev.jiaming.ai_interview.common.ApiRequestException;
import dev.jiaming.ai_interview.gemini.GeminiException;
import dev.jiaming.ai_interview.gemini.GeminiErrorCode;
import dev.jiaming.ai_interview.resume.ResumeExtractionException;
import dev.jiaming.ai_interview.resume.ResumeParserBusyException;

class JobFailureClassifierTests {

	private final JobFailureClassifier classifier = new JobFailureClassifier();

	@Test
	void retriesGeminiRateLimitsAndServerErrors() {
		JobFailure rateLimit = classifier.classify(new GeminiException("quota", 429, true));
		assertThat(rateLimit.code()).isEqualTo(GeminiErrorCode.RATE_LIMITED);
		assertThat(rateLimit.retryable()).isTrue();
		assertThat(classifier.classify(new ResponseStatusException(HttpStatus.BAD_GATEWAY)).retryable()).isTrue();
	}

	@Test
	void preservesGeminiRetryMetadataForEmptyAndTerminalFinishReasons() {
		JobFailure empty = classifier.classify(new GeminiException(
			GeminiErrorCode.EMPTY_RESPONSE, "empty", true
		));
		JobFailure maxTokens = classifier.classify(new GeminiException(
			GeminiErrorCode.MAX_TOKENS, "truncated", false
		));

		assertThat(empty.code()).isEqualTo(GeminiErrorCode.EMPTY_RESPONSE);
		assertThat(empty.retryable()).isTrue();
		assertThat(maxTokens.code()).isEqualTo(GeminiErrorCode.MAX_TOKENS);
		assertThat(maxTokens.retryable()).isFalse();
	}

	@Test
	void preservesPermanentApiReferenceFailureCode() {
		JobFailure failure = classifier.classify(new ApiRequestException(
			HttpStatus.CONFLICT, "REFERENCE_MISMATCH", "Document reference does not match"
		));

		assertThat(failure.code()).isEqualTo("REFERENCE_MISMATCH");
		assertThat(failure.retryable()).isFalse();
	}

	@Test
	void retriesTemporaryParserSaturation() {
		JobFailure failure = classifier.classify(new ResumeParserBusyException());

		assertThat(failure.code()).isEqualTo("RESUME_PARSER_BUSY");
		assertThat(failure.retryable()).isTrue();
	}

	@Test
	void doesNotRetryInvalidDocumentsOrClientErrors() {
		assertThat(classifier.classify(new ResumeExtractionException("Encrypted PDF")).retryable()).isFalse();
		assertThat(classifier.classify(new ResponseStatusException(HttpStatus.BAD_REQUEST)).retryable()).isFalse();
		assertThat(classifier.classify(new IllegalArgumentException("bad payload")).retryable()).isFalse();
	}
}
