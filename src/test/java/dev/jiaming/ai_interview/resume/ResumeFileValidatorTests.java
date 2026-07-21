package dev.jiaming.ai_interview.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

class ResumeFileValidatorTests {

	private static final long MAX_BYTES = 10L * 1024 * 1024;

	private final ResumeFileValidator validator = new ResumeFileValidator();

	@Test
	void acceptsEverySupportedExtensionCaseInsensitively() {
		for (String name : new String[] { "resume.pdf", "resume.doc", "resume.docx", "resume.txt", "resume.md", "RESUME.PDF" }) {
			MultipartFile file = new MockMultipartFile("file", name, "application/octet-stream", new byte[] { 1, 2, 3 });
			assertThatCode(() -> validator.validate(file)).doesNotThrowAnyException();
		}
	}

	@Test
	void rejectsAMissingOrEmptyFile() {
		MultipartFile empty = new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[0]);

		assertThatThrownBy(() -> validator.validate(empty))
			.isInstanceOfSatisfying(ResponseStatusException.class, exception ->
				assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
		assertThatThrownBy(() -> validator.validate(null))
			.isInstanceOf(ResponseStatusException.class);
	}

	@Test
	void rejectsAFileLargerThanTheLimit() {
		MultipartFile oversized = new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[] { 1 }) {
			@Override
			public long getSize() {
				return MAX_BYTES + 1;
			}
		};

		assertThatThrownBy(() -> validator.validate(oversized))
			.isInstanceOfSatisfying(ResponseStatusException.class, exception ->
				assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
	}

	@Test
	void rejectsAnUnsupportedOrMissingExtension() {
		MultipartFile executable = new MockMultipartFile("file", "resume.exe", "application/octet-stream", new byte[] { 1 });
		MultipartFile noExtension = new MockMultipartFile("file", "resume", "application/octet-stream", new byte[] { 1 });

		for (MultipartFile file : new MultipartFile[] { executable, noExtension }) {
			assertThatThrownBy(() -> validator.validate(file))
				.isInstanceOfSatisfying(ResponseStatusException.class, exception ->
					assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
		}
	}
}
