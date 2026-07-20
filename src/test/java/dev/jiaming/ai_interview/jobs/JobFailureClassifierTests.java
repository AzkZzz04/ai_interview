package dev.jiaming.ai_interview.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import dev.jiaming.ai_interview.gemini.GeminiException;
import dev.jiaming.ai_interview.resume.ResumeExtractionException;
import dev.jiaming.ai_interview.resume.ResumeParserBusyException;

class JobFailureClassifierTests {

	private final JobFailureClassifier classifier = new JobFailureClassifier();

	@Test
	void retriesGeminiRateLimitsAndServerErrors() {
		assertThat(classifier.classify(new GeminiException("quota", 429, true)).retryable()).isTrue();
		assertThat(classifier.classify(new ResponseStatusException(HttpStatus.BAD_GATEWAY)).retryable()).isTrue();
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
