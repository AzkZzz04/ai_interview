package dev.jiaming.ai_interview.resume

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockMultipartFile
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException

class ResumeFileValidatorTests {
	private val validator = ResumeFileValidator()

	@Test
	fun acceptsEverySupportedExtensionCaseInsensitively() {
		for (name in listOf("resume.pdf", "resume.doc", "resume.docx", "resume.txt", "resume.md", "RESUME.PDF")) {
			val file: MultipartFile = MockMultipartFile("file", name, "application/octet-stream", byteArrayOf(1, 2, 3))
			assertThatCode { validator.validate(file) }.doesNotThrowAnyException()
		}
	}

	@Test
	fun rejectsAMissingOrEmptyFile() {
		val empty: MultipartFile = MockMultipartFile("file", "resume.pdf", "application/pdf", ByteArray(0))
		assertThatThrownBy { validator.validate(empty) }.isInstanceOfSatisfying(ResponseStatusException::class.java) { exception ->
			assertThat(exception.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
		}
		assertThatThrownBy { validator.validate(null) }.isInstanceOf(ResponseStatusException::class.java)
	}

	@Test
	fun rejectsAFileLargerThanTheLimit() {
		val oversized: MultipartFile = object : MockMultipartFile("file", "resume.pdf", "application/pdf", byteArrayOf(1)) {
			override fun getSize() = MAX_BYTES + 1
		}
		assertThatThrownBy { validator.validate(oversized) }.isInstanceOfSatisfying(ResponseStatusException::class.java) { exception ->
			assertThat(exception.statusCode).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE)
		}
	}

	@Test
	fun rejectsAnUnsupportedOrMissingExtension() {
		val executable: MultipartFile = MockMultipartFile("file", "resume.exe", "application/octet-stream", byteArrayOf(1))
		val noExtension: MultipartFile = MockMultipartFile("file", "resume", "application/octet-stream", byteArrayOf(1))
		for (file in listOf(executable, noExtension)) {
			assertThatThrownBy { validator.validate(file) }.isInstanceOfSatisfying(ResponseStatusException::class.java) { exception ->
				assertThat(exception.statusCode).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
			}
		}
	}

	private companion object {
		const val MAX_BYTES = 10L * 1024 * 1024
	}
}
