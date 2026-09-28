package dev.jiaming.ai_interview.resume

import com.fasterxml.jackson.databind.JsonNode
import dev.jiaming.ai_interview.jobs.JobExecutionContext
import dev.jiaming.ai_interview.jobs.JobHandler
import dev.jiaming.ai_interview.jobs.JobStage
import dev.jiaming.ai_interview.jobs.JobType
import org.springframework.stereotype.Service

@Service
class ResumeExtractionJobHandler(
    private val storageService: ResumeStorageService,
    private val textExtractor: ResumeTextExtractor,
    private val normalizer: ResumeTextNormalizer,
    private val chunker: SectionAwareTextChunker,
    private val persistenceService: ResumePersistenceService
) : JobHandler<ResumeExtractionJobPayload> {
    override fun type() = JobType.RESUME_EXTRACTION
    override fun payloadType() = ResumeExtractionJobPayload::class.java

    override fun handle(payload: ResumeExtractionJobPayload, context: JobExecutionContext): JsonNode {
        context.stage(JobStage.READING_FILE)
        val content = storageService.read(payload)
        context.stage(JobStage.EXTRACTING_TEXT)
        val rawText = textExtractor.extract(content)
        context.stage(JobStage.NORMALIZING_TEXT)
        val normalizedText = normalizer.normalize(rawText)
        if (normalizedText.isBlank()) throw ResumeExtractionException("No readable resume text was extracted")
        context.stage(JobStage.CHUNKING_TEXT)
        val chunks = chunker.chunk(normalizedText).map {
            ResumeChunkResponse(it.index, it.section, it.content, it.content.length)
        }
        val response = context.withOwnedLease {
            persistenceService.completeExtraction(payload.resumeId, rawText, normalizedText, chunks)
        }
        storageService.markReady(payload.storageKey)
        return context.toJson(response)
    }
}
