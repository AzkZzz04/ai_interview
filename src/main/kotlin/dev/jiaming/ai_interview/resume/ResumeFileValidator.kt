package dev.jiaming.ai_interview.resume

import java.util.Locale
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException

@Component
class ResumeFileValidator {
    fun validate(file: MultipartFile?) {
        if (file == null || file.isEmpty) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Resume file is required")
        }
        if (file.size > MAX_FILE_BYTES) {
            throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Resume file must be 10 MB or smaller")
        }
        if (extension(file.originalFilename) !in ALLOWED_EXTENSIONS) {
            throw ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Resume must be a PDF, DOC, DOCX, TXT, or Markdown file")
        }
    }

    private fun extension(filename: String?): String = if (filename == null || !filename.contains('.')) ""
        else filename.substring(filename.lastIndexOf('.') + 1).lowercase(Locale.ROOT)

    companion object {
        private const val MAX_FILE_BYTES = 10 * 1024 * 1024L
        private val ALLOWED_EXTENSIONS = setOf("pdf", "doc", "docx", "txt", "text", "md", "markdown")
    }
}
