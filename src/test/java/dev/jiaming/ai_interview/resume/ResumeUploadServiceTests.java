package dev.jiaming.ai_interview.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class ResumeUploadServiceTests {

	private final ResumeUploadService service = new ResumeUploadService(
		new ResumeTextNormalizer(),
		new SectionAwareTextChunker()
	);

	@Test
	void extractsAndChunksTextResume() {
		MockMultipartFile file = new MockMultipartFile(
			"file",
			"resume.txt",
			"text/plain",
			"""
				SKILLS
				Java, Spring Boot, PostgreSQL

				EXPERIENCE
				Built resume parsing APIs.
				""".getBytes(StandardCharsets.UTF_8)
		);

		ResumeUploadResponse response = service.process(file);

		assertThat(response.originalFilename()).isEqualTo("resume.txt");
		assertThat(response.detectedContentType()).startsWith("text/plain");
		assertThat(response.normalizedText()).contains("Java, Spring Boot, PostgreSQL");
		assertThat(response.chunks()).extracting("section")
			.contains("Skills", "Experience");
		assertThat(service.current()).isEmpty();
	}

	@Test
	void extractsTextFromPdfResume() throws IOException {
		MockMultipartFile file = new MockMultipartFile(
			"file",
			"resume.pdf",
			"application/pdf",
			createPdf("Jane Doe Java Spring PostgreSQL")
		);

		ResumeUploadResponse response = service.process(file);

		assertThat(response.originalFilename()).isEqualTo("resume.pdf");
		assertThat(response.detectedContentType()).isEqualTo("application/pdf");
		assertThat(response.normalizedText()).contains("Jane Doe Java Spring PostgreSQL");
		assertThat(response.chunks()).isNotEmpty();
	}

	@Test
	void rejectsUnsupportedFileTypes() {
		MockMultipartFile file = new MockMultipartFile(
			"file",
			"resume.png",
			"image/png",
			new byte[] { 1, 2, 3 }
		);

		assertThatThrownBy(() -> service.process(file))
			.isInstanceOf(ResponseStatusException.class)
			.hasMessageContaining("415 UNSUPPORTED_MEDIA_TYPE");
	}

	private byte[] createPdf(String text) throws IOException {
		try (
			PDDocument document = new PDDocument();
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream()
		) {
			PDPage page = new PDPage();
			document.addPage(page);

			try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
				contentStream.beginText();
				contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				contentStream.newLineAtOffset(72, 720);
				contentStream.showText(text);
				contentStream.endText();
			}

			document.save(outputStream);
			return outputStream.toByteArray();
		}
	}
}
