package dev.jiaming.ai_interview.resume

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Locale
import org.apache.tika.Tika
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.springframework.stereotype.Component
import org.springframework.web.multipart.MultipartFile

@Component
class ResumeFileReader {
    private val tika = Tika()

    fun read(file: MultipartFile): ResumeFileContent {
        val bytes = try {
            file.bytes
        } catch (exception: IOException) {
            throw ResumeExtractionException("Failed to read resume file", exception)
        }
        return ResumeFileContent(
            file.originalFilename, file.contentType, file.size, bytes,
            detectContentType(file, bytes), extension(file.originalFilename)
        )
    }

    private fun detectContentType(file: MultipartFile, fileBytes: ByteArray): String? {
        val metadata = Metadata()
        file.originalFilename?.let { metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, it) }
        return try {
            ByteArrayInputStream(fileBytes).use { tika.detect(it, metadata) }
        } catch (_: IOException) {
            file.contentType
        }
    }

    private fun extension(filename: String?): String = if (filename == null || !filename.contains('.')) ""
        else filename.substring(filename.lastIndexOf('.') + 1).lowercase(Locale.ROOT)
}
