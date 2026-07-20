package dev.jiaming.ai_interview.resume;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import jakarta.annotation.PreDestroy;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.ParsingEmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

@Component
public class ResumeTextExtractor {

	private static final int EXTRACTION_THREADS = 2;

	private final ResumeExtractionProperties properties;

	private final Function<ResumeFileContent, String> parserOverride;

	private final ThreadPoolExecutor extractionExecutor;

	@Autowired
	public ResumeTextExtractor(ResumeExtractionProperties properties) {
		this(properties, null);
	}

	public ResumeTextExtractor() {
		this(new ResumeExtractionProperties(2, 20, 250_000, 50, 20));
	}

	ResumeTextExtractor(
		ResumeExtractionProperties properties,
		Function<ResumeFileContent, String> parserOverride
	) {
		this.properties = properties;
		this.parserOverride = parserOverride;
		this.extractionExecutor = new ThreadPoolExecutor(
			EXTRACTION_THREADS,
			EXTRACTION_THREADS,
			0,
			TimeUnit.MILLISECONDS,
			new ArrayBlockingQueue<>(properties.queueCapacity()),
			new ResumeExtractionThreadFactory(),
			new ThreadPoolExecutor.AbortPolicy()
		);
	}

	public String extract(ResumeFileContent fileContent) {
		Future<String> extraction;
		try {
			extraction = extractionExecutor.submit(() -> extractWithoutTimeout(fileContent));
		}
		catch (RejectedExecutionException exception) {
			throw new ResumeParserBusyException();
		}

		try {
			return extraction.get(properties.timeoutSeconds(), TimeUnit.SECONDS);
		}
		catch (TimeoutException exception) {
			extraction.cancel(true);
			throw new ResumeExtractionException(
				"Resume text extraction timed out after " + properties.timeoutSeconds()
					+ " seconds. This PDF may be scanned, encrypted, or malformed.",
				exception
			);
		}
		catch (InterruptedException exception) {
			extraction.cancel(true);
			Thread.currentThread().interrupt();
			throw new ResumeExtractionException("Resume text extraction was interrupted", exception);
		}
		catch (ExecutionException exception) {
			Throwable cause = exception.getCause() == null ? exception : exception.getCause();
			if (cause instanceof ResumeExtractionException resumeExtractionException) {
				throw resumeExtractionException;
			}
			throw new ResumeExtractionException("Failed to extract resume text", cause);
		}
	}

	@PreDestroy
	void shutdownExtractionExecutor() {
		extractionExecutor.shutdownNow();
	}

	int activeExtractions() {
		return extractionExecutor.getActiveCount();
	}

	int queuedExtractions() {
		return extractionExecutor.getQueue().size();
	}

	private String extractWithoutTimeout(ResumeFileContent fileContent) {
		if (parserOverride != null) {
			return parserOverride.apply(fileContent);
		}
		if ("pdf".equals(fileContent.extension()) || "application/pdf".equalsIgnoreCase(fileContent.detectedContentType())) {
			return extractPdf(fileContent.bytes());
		}
		return extractWithTika(fileContent);
	}

	private String extractWithTika(ResumeFileContent fileContent) {
		Metadata metadata = new Metadata();
		if (fileContent.originalFilename() != null) {
			metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileContent.originalFilename());
		}
		if (fileContent.contentType() != null) {
			metadata.set(Metadata.CONTENT_TYPE, fileContent.contentType());
		}

		AutoDetectParser parser = new AutoDetectParser();
		ParseContext context = new ParseContext();
		context.set(org.apache.tika.parser.Parser.class, parser);
		limitEmbeddedResources(context);
		ContentHandler handler = new BodyContentHandler(properties.maxParseChars());
		try (InputStream inputStream = new java.io.ByteArrayInputStream(fileContent.bytes())) {
			parser.parse(inputStream, handler, metadata, context);
			return handler.toString();
		}
		catch (IOException | TikaException | SAXException exception) {
			throw new ResumeExtractionException("Failed to extract resume text", exception);
		}
	}

	private void limitEmbeddedResources(ParseContext context) {
		EmbeddedDocumentExtractor delegate = new ParsingEmbeddedDocumentExtractor(context);
		AtomicInteger embeddedCount = new AtomicInteger();
		context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
			@Override
			public boolean shouldParseEmbedded(Metadata metadata) {
				return embeddedCount.get() < properties.maxEmbeddedResources()
					&& delegate.shouldParseEmbedded(metadata);
			}

			@Override
			public void parseEmbedded(
				InputStream stream,
				ContentHandler handler,
				Metadata metadata,
				boolean outputHtml
			) throws SAXException, IOException {
				if (embeddedCount.incrementAndGet() > properties.maxEmbeddedResources()) {
					throw new SAXException("Resume contains too many embedded resources");
				}
				delegate.parseEmbedded(stream, handler, metadata, outputHtml);
			}
		});
	}

	private String extractPdf(byte[] fileBytes) {
		try (PDDocument document = Loader.loadPDF(fileBytes)) {
			if (document.isEncrypted()) {
				throw new ResumeExtractionException("Encrypted PDFs are not supported");
			}
			if (document.getNumberOfPages() > properties.maxPdfPages()) {
				throw new ResumeExtractionException(
					"PDF resumes may contain at most " + properties.maxPdfPages() + " pages"
				);
			}
			PDFTextStripper stripper = new PDFTextStripper();
			stripper.setSortByPosition(true);
			stripper.setSuppressDuplicateOverlappingText(true);
			return stripper.getText(document);
		}
		catch (IOException exception) {
			throw new ResumeExtractionException("Failed to extract PDF text", exception);
		}
	}

	private static final class ResumeExtractionThreadFactory implements ThreadFactory {

		private int threadNumber = 1;

		@Override
		public synchronized Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, "resume-extraction-" + threadNumber++);
			thread.setDaemon(true);
			return thread;
		}
	}
}
