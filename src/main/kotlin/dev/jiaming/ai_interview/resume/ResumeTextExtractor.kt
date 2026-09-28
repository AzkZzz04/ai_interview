package dev.jiaming.ai_interview.resume

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import jakarta.annotation.PreDestroy
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.tika.exception.TikaException
import org.apache.tika.extractor.EmbeddedDocumentExtractor
import org.apache.tika.extractor.ParsingEmbeddedDocumentExtractor
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.ParseContext
import org.apache.tika.sax.BodyContentHandler
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import org.xml.sax.ContentHandler
import org.xml.sax.SAXException

@Component
class ResumeTextExtractor(
    private val properties: ResumeExtractionProperties,
    private val parserOverride: ((ResumeFileContent) -> String)?
) {
    @Autowired
    constructor(properties: ResumeExtractionProperties) : this(properties, null)

    constructor() : this(ResumeExtractionProperties(2, 20, 250_000, 50, 20), null)

    private val extractionExecutor = ThreadPoolExecutor(
        EXTRACTION_THREADS, EXTRACTION_THREADS, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(properties.queueCapacity), ResumeExtractionThreadFactory(), ThreadPoolExecutor.AbortPolicy()
    )

    fun extract(fileContent: ResumeFileContent): String {
        val extraction: Future<String> = try {
            extractionExecutor.submit<String> { extractWithoutTimeout(fileContent) }
        } catch (_: RejectedExecutionException) {
            throw ResumeParserBusyException()
        }
        try {
            return extraction.get(properties.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        } catch (exception: TimeoutException) {
            extraction.cancel(true)
            throw ResumeExtractionException(
                "Resume text extraction timed out after ${properties.timeoutSeconds} seconds. This PDF may be scanned, encrypted, or malformed.",
                exception
            )
        } catch (exception: InterruptedException) {
            extraction.cancel(true)
            Thread.currentThread().interrupt()
            throw ResumeExtractionException("Resume text extraction was interrupted", exception)
        } catch (exception: ExecutionException) {
            val cause = exception.cause ?: exception
            if (cause is ResumeExtractionException) throw cause
            throw ResumeExtractionException("Failed to extract resume text", cause)
        }
    }

    @PreDestroy
    fun shutdownExtractionExecutor() { extractionExecutor.shutdownNow() }

    fun activeExtractions() = extractionExecutor.activeCount
    fun queuedExtractions() = extractionExecutor.queue.size

    private fun extractWithoutTimeout(fileContent: ResumeFileContent): String {
        parserOverride?.let { return it(fileContent) }
        return if (fileContent.extension == "pdf" || fileContent.detectedContentType.equals("application/pdf", true)) {
            extractPdf(fileContent.bytes)
        } else extractWithTika(fileContent)
    }

    private fun extractWithTika(fileContent: ResumeFileContent): String {
        val metadata = Metadata()
        fileContent.originalFilename?.let { metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, it) }
        fileContent.contentType?.let { metadata.set(Metadata.CONTENT_TYPE, it) }
        val parser = AutoDetectParser()
        val context = ParseContext()
        context.set(org.apache.tika.parser.Parser::class.java, parser)
        limitEmbeddedResources(context)
        val handler = BodyContentHandler(properties.maxParseChars)
        try {
            ByteArrayInputStream(fileContent.bytes).use { parser.parse(it, handler, metadata, context) }
            return handler.toString()
        } catch (exception: IOException) {
            throw ResumeExtractionException("Failed to extract resume text", exception)
        } catch (exception: TikaException) {
            throw ResumeExtractionException("Failed to extract resume text", exception)
        } catch (exception: SAXException) {
            throw ResumeExtractionException("Failed to extract resume text", exception)
        }
    }

    private fun limitEmbeddedResources(context: ParseContext) {
        val delegate = ParsingEmbeddedDocumentExtractor(context)
        val embeddedCount = AtomicInteger()
        context.set(EmbeddedDocumentExtractor::class.java, object : EmbeddedDocumentExtractor {
            override fun shouldParseEmbedded(metadata: Metadata) =
                embeddedCount.get() < properties.maxEmbeddedResources && delegate.shouldParseEmbedded(metadata)

            @Throws(SAXException::class, IOException::class)
            override fun parseEmbedded(stream: java.io.InputStream, handler: ContentHandler, metadata: Metadata, outputHtml: Boolean) {
                if (embeddedCount.incrementAndGet() > properties.maxEmbeddedResources) {
                    throw SAXException("Resume contains too many embedded resources")
                }
                delegate.parseEmbedded(stream, handler, metadata, outputHtml)
            }
        })
    }

    private fun extractPdf(fileBytes: ByteArray): String {
        try {
            Loader.loadPDF(fileBytes).use { document: PDDocument ->
                if (document.isEncrypted) throw ResumeExtractionException("Encrypted PDFs are not supported")
                if (document.numberOfPages > properties.maxPdfPages) {
                    throw ResumeExtractionException("PDF resumes may contain at most ${properties.maxPdfPages} pages")
                }
                return PDFTextStripper().apply {
                    setSortByPosition(true)
                    setSuppressDuplicateOverlappingText(true)
                }.getText(document)
            }
        } catch (exception: IOException) {
            throw ResumeExtractionException("Failed to extract PDF text", exception)
        }
    }

    private class ResumeExtractionThreadFactory : ThreadFactory {
        private var threadNumber = 1
        @Synchronized
        override fun newThread(runnable: Runnable): Thread = Thread(runnable, "resume-extraction-${threadNumber++}").apply {
            isDaemon = true
        }
    }

    private companion object { const val EXTRACTION_THREADS = 2 }
}
