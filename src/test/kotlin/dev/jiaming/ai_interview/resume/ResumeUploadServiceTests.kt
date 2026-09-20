package dev.jiaming.ai_interview.resume

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import org.springframework.web.server.ResponseStatusException

class ResumeUploadServiceTests {
	private val service = ResumeUploadService(ResumeTextNormalizer(), SectionAwareTextChunker())

	@Test
	fun extractsAndChunksTextResume() {
		val file = MockMultipartFile("file", "resume.txt", "text/plain", """
			SKILLS
			Java, Spring Boot, PostgreSQL

			EXPERIENCE
			Built resume parsing APIs.
		""".trimIndent().toByteArray(StandardCharsets.UTF_8))
		val response = service.process(file)
		assertThat(response.originalFilename()).isEqualTo("resume.txt")
		assertThat(response.detectedContentType()).startsWith("text/plain")
		assertThat(response.normalizedText()).contains("Java, Spring Boot, PostgreSQL")
		assertThat(response.chunks().map { it.section() }).contains("Skills", "Experience")
		assertThat(service.current()).isEmpty()
	}

	@Test
	fun extractsTextFromPdfResume() {
		val file = MockMultipartFile("file", "resume.pdf", "application/pdf", createPdf("Jane Doe Java Spring PostgreSQL"))
		val response = service.process(file)
		assertThat(response.originalFilename()).isEqualTo("resume.pdf")
		assertThat(response.detectedContentType()).isEqualTo("application/pdf")
		assertThat(response.normalizedText()).contains("Jane Doe Java Spring PostgreSQL")
		assertThat(response.chunks()).isNotEmpty()
	}

	@Test
	fun rejectsUnsupportedFileTypes() {
		val file = MockMultipartFile("file", "resume.png", "image/png", byteArrayOf(1, 2, 3))
		assertThatThrownBy { service.process(file) }.isInstanceOf(ResponseStatusException::class.java).hasMessageContaining("415 UNSUPPORTED_MEDIA_TYPE")
	}

	private fun createPdf(text: String): ByteArray = PDDocument().use { document ->
		ByteArrayOutputStream().use { outputStream ->
			val page = PDPage(); document.addPage(page)
			PDPageContentStream(document, page).use { contentStream ->
				contentStream.beginText(); contentStream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f); contentStream.newLineAtOffset(72f, 720f); contentStream.showText(text); contentStream.endText()
			}
			document.save(outputStream); outputStream.toByteArray()
		}
	}
}
