package dev.jiaming.ai_interview.document

enum class DocumentSourceType(private val metadata: String) {
    RESUME("resume"),
    JOB_DESCRIPTION("job_description"),
    ;

    fun metadataValue(): String = metadata
}
