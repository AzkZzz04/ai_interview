package dev.jiaming.ai_interview.rag

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.ConstructorBinding

@ConfigurationProperties(prefix = "app.rag")
class RagProperties @ConstructorBinding constructor(
    embeddingDimensions: Int,
    defaultTopK: Int,
    resumeCandidateTopK: Int,
    jobDescriptionCandidateTopK: Int,
    assessmentContextBudget: Int,
    questionContextBudget: Int,
    feedbackContextBudget: Int,
    assessmentJobDescriptionMinimum: Int,
    questionJobDescriptionMinimum: Int,
    feedbackJobDescriptionMinimum: Int,
    sectionMaximum: Int,
    rrfK: Int,
    embeddingModel: String?,
    chunkSchema: String?
) {
    private val dimensions = embeddingDimensions.takeIf { it > 0 } ?: 1024
    private val topK = defaultTopK.takeIf { it > 0 } ?: 8
    private val resumeTopK = resumeCandidateTopK.takeIf { it > 0 } ?: 6
    private val jdTopK = jobDescriptionCandidateTopK.takeIf { it > 0 } ?: 4
    private val assessmentBudget = assessmentContextBudget.takeIf { it > 0 } ?: 14
    private val questionBudget = questionContextBudget.takeIf { it > 0 } ?: 16
    private val feedbackBudget = feedbackContextBudget.takeIf { it > 0 } ?: 10
    private val assessmentJdMinimum = assessmentJobDescriptionMinimum.coerceAtLeast(0)
    private val questionJdMinimum = questionJobDescriptionMinimum.coerceAtLeast(0)
    private val feedbackJdMinimum = feedbackJobDescriptionMinimum.coerceAtLeast(0)
    private val maxSection = sectionMaximum.takeIf { it > 0 } ?: 3
    private val reciprocalRankK = rrfK.takeIf { it > 0 } ?: 15
    private val model = embeddingModel?.takeIf { it.isNotBlank() }?.trim() ?: "gemini-embedding-001"
    private val schema = chunkSchema?.takeIf { it.isNotBlank() }?.trim() ?: "section-block-v3"

    constructor(embeddingDimensions: Int, defaultTopK: Int, embeddingModel: String?, chunkSchema: String?) :
        this(embeddingDimensions, defaultTopK, 6, 4, 14, 16, 10, 3, 3, 2, 3, 15, embeddingModel, chunkSchema)

    fun embeddingDimensions() = dimensions
    fun defaultTopK() = topK
    fun resumeCandidateTopK() = resumeTopK
    fun jobDescriptionCandidateTopK() = jdTopK
    fun assessmentContextBudget() = assessmentBudget
    fun questionContextBudget() = questionBudget
    fun feedbackContextBudget() = feedbackBudget
    fun assessmentJobDescriptionMinimum() = assessmentJdMinimum
    fun questionJobDescriptionMinimum() = questionJdMinimum
    fun feedbackJobDescriptionMinimum() = feedbackJdMinimum
    fun sectionMaximum() = maxSection
    fun rrfK() = reciprocalRankK
    fun embeddingModel() = model
    fun chunkSchema() = schema
}
