package dev.jiaming.ai_interview.coach

import dev.jiaming.ai_interview.document.DocumentChunk
import dev.jiaming.ai_interview.document.DocumentSourceType
import dev.jiaming.ai_interview.document.ResolvedDocument
import dev.jiaming.ai_interview.rag.RagContextSnippet
import dev.jiaming.ai_interview.rag.RagDocumentIndexHandle
import dev.jiaming.ai_interview.rag.RagIndexingService
import dev.jiaming.ai_interview.rag.RagRetrievalService
import dev.jiaming.ai_interview.resume.SectionAwareTextChunker
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.eq
import org.mockito.Mockito
import java.util.Optional
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class CoachRagContextServiceTests {

    @Test
    fun shortDocumentsUseEveryPersistedChunkWithoutEmbedding() {
        val indexingService = Mockito.mock(RagIndexingService::class.java)
        val retrievalService = Mockito.mock(RagRetrievalService::class.java)
        val service = service(indexingService, retrievalService)
        val resume = document(DocumentSourceType.RESUME, "resume-hash", "short resume", listOf(
            DocumentChunk(0, "Experience", "Built an API", "resume:experience:0"),
            DocumentChunk(1, "Projects", "Built an AI coach", "resume:projects:1")
        ))
        val jobDescription = document(DocumentSourceType.JOB_DESCRIPTION, "jd-hash", "short job description", listOf(
            DocumentChunk(0, "Requirements", "Java required", "job_description:requirements:0")
        ))

        val context = service.jobFitContext(CoachAnalysisInput(resume, Optional.of(jobDescription)))

        assertThat(context.vectorBacked).isFalse()
        assertThat(context.context).contains("Built an API", "Built an AI coach", "Java required")
        assertThat(context.sourceContextIds).containsExactly(
            "resume:experience:0", "resume:projects:1", "job_description:requirements:0"
        )
        Mockito.verifyNoInteractions(indexingService, retrievalService)
    }

    @Test
    fun jobFitContextUsesResumeAndTargetJobRequirementsFromSharedRetrieval() {
        val indexingService = Mockito.mock(RagIndexingService::class.java)
        val retrievalService = Mockito.mock(RagRetrievalService::class.java)
        val service = service(indexingService, retrievalService)
        val resume = document(DocumentSourceType.RESUME, "resume-hash", "short resume", listOf(
            DocumentChunk(0, "Experience", "Delivered Kotlin services", "resume:experience:0")
        ))
        val jobDescription = document(DocumentSourceType.JOB_DESCRIPTION, "jd-hash", "short job description", listOf(
            DocumentChunk(0, "Requirements", "Kafka and PostgreSQL required", "job_description:requirements:0")
        ))

        val context = service.jobFitContext(CoachAnalysisInput(resume, Optional.of(jobDescription)))

        assertThat(context.context).contains("Delivered Kotlin services", "Kafka and PostgreSQL required")
        assertThat(context.sourceContextIds).containsExactly("resume:experience:0", "job_description:requirements:0")
        Mockito.verifyNoInteractions(indexingService, retrievalService)
    }

    @Test
    fun practiceQuestionRetrievalUsesTheQuestionQueries() {
        val indexingService = Mockito.mock(RagIndexingService::class.java)
        val retrievalService = Mockito.mock(RagRetrievalService::class.java)
        val service = service(indexingService, retrievalService)
        val resume = document(DocumentSourceType.RESUME, "resume-hash", "R".repeat(6_100), listOf(
            DocumentChunk(0, "Experience", "Resume experience", "resume:experience:0")
        ))
        val handle = RagDocumentIndexHandle(UUID.randomUUID(), 1L)
        Mockito.`when`(indexingService.ensureIndexed(resume)).thenReturn(Optional.of(handle))
        Mockito.`when`(retrievalService.retrieve(anyString(), any<RagDocumentIndexHandle>(), eq(6))).thenReturn(emptyList())

        service.practiceQuestionContext(CoachAnalysisInput(resume, Optional.empty()))

        val queries = org.mockito.kotlin.argumentCaptor<String>()
        Mockito.verify(retrievalService, Mockito.times(6)).retrieve(queries.capture(), any<RagDocumentIndexHandle>(), eq(6))
        assertThat(queries.allValues.first()).startsWith("strongest projects ownership technical complexity")
        assertThat(queries.allValues).noneMatch { it.contains("Mid-level") }
    }

    @Test
    fun suggestionSourceTextKeepsAResumeWithinBudgetWholeAndNarrowsALongerOneAgainstTheJobDescription() {
        val indexingService = Mockito.mock(RagIndexingService::class.java)
        val retrievalService = Mockito.mock(RagRetrievalService::class.java)
        val service = service(indexingService, retrievalService)
        val jobDescription = document(DocumentSourceType.JOB_DESCRIPTION, "jd-hash", "Kafka and event-driven systems required", emptyList())
        val short = document(DocumentSourceType.RESUME, "short-hash", "Short resume text", emptyList())

        assertThat(service.suggestionSourceText(short, jobDescription)).isEqualTo("Short resume text")
        Mockito.verifyNoInteractions(indexingService, retrievalService)

        val long = document(DocumentSourceType.RESUME, "long-hash", "R".repeat(6_100), listOf(
            DocumentChunk(0, "Experience", "Ran the Kafka pipeline", "resume:experience:0")
        ))
        val handle = RagDocumentIndexHandle(UUID.randomUUID(), 1L)
        Mockito.`when`(indexingService.ensureIndexed(long)).thenReturn(Optional.of(handle))
        Mockito.`when`(retrievalService.retrieve(anyString(), eq(handle), eq(6))).thenReturn(listOf(
            snippet("resume:experience:0", "resume", "Experience", 0, "Ran the Kafka pipeline")
        ))

        val narrowed = service.suggestionSourceText(long, jobDescription)

        assertThat(narrowed).contains("Ran the Kafka pipeline").doesNotContain("R".repeat(100))
        Mockito.verify(retrievalService, Mockito.atLeastOnce()).retrieve(Mockito.contains("Kafka and event-driven systems required"), eq(handle), eq(6))
        Mockito.verify(indexingService, Mockito.never()).ensureIndexed(jobDescription)
    }

    @Test
    fun localDocumentContextIsReservedWhenAnotherDocumentUsesVectorRetrieval() {
        val indexingService = Mockito.mock(RagIndexingService::class.java)
        val retrievalService = Mockito.mock(RagRetrievalService::class.java)
        val service = service(indexingService, retrievalService)
        val resume = document(DocumentSourceType.RESUME, "resume-hash", "R".repeat(6_100), listOf(
            DocumentChunk(0, "Experience", "Resume experience", "resume:experience:0")
        ))
        val jobDescription = document(DocumentSourceType.JOB_DESCRIPTION, "jd-hash", "Java and distributed systems are required", listOf(
            DocumentChunk(0, "Requirements", "Java and distributed systems are required", "job_description:requirements:0")
        ))
        val handle = RagDocumentIndexHandle(UUID.randomUUID(), 1L)
        Mockito.`when`(indexingService.ensureIndexed(resume)).thenReturn(Optional.of(handle))
        Mockito.`when`(indexingService.ensureIndexed(jobDescription)).thenReturn(Optional.empty())
        Mockito.`when`(retrievalService.retrieve(anyString(), any<RagDocumentIndexHandle>(), eq(6))).thenReturn(
            (0 until 14).map { index -> RagContextSnippet(
                "resume-vector-$index", "Resume vector context $index",
                mapOf("contextId" to "resume:experience:$index", "sourceType" to "resume", "section" to "Experience"), 0.9
            ) }
        )

        val context = service.jobFitContext(CoachAnalysisInput(resume, Optional.of(jobDescription)))

        assertThat(context.vectorBacked).isTrue()
        assertThat(context.context).contains("Java and distributed systems are required")
        assertThat(context.sourceContextIds).contains("job_description:requirements:0")
    }

    @Test
    fun retrievalFailureUsesLocalContextAndIsNotReportedAsVectorBacked() {
        val indexingService = Mockito.mock(RagIndexingService::class.java)
        val retrievalService = Mockito.mock(RagRetrievalService::class.java)
        val service = service(indexingService, retrievalService)
        val resume = document(DocumentSourceType.RESUME, "resume-hash", "R".repeat(6_100), listOf(
            DocumentChunk(0, "Experience", "Resume experience", "resume:experience:0")
        ))
        val handle = RagDocumentIndexHandle(UUID.randomUUID(), 1L)
        Mockito.`when`(indexingService.ensureIndexed(resume)).thenReturn(Optional.of(handle))
        Mockito.`when`(retrievalService.retrieve(anyString(), any<RagDocumentIndexHandle>(), eq(6)))
            .thenThrow(IllegalStateException("vector store unavailable"))

        val context = service.jobFitContext(CoachAnalysisInput(resume, Optional.empty()))

        assertThat(context.vectorBacked).isFalse()
        assertThat(context.context).contains("Resume experience")
        assertThat(context.sourceContextIds).containsExactly("resume:experience:0")
    }

    @Test
    fun reservesJobDescriptionEvidenceAndRestoresOriginalChunkText() {
        val indexingService = Mockito.mock(RagIndexingService::class.java)
        val retrievalService = Mockito.mock(RagRetrievalService::class.java)
        val service = service(indexingService, retrievalService)
        val resume = document(DocumentSourceType.RESUME, "resume-hash", "R".repeat(6_100),
            (0 until 6).map { index -> DocumentChunk(index, "Experience", "Resume evidence $index", "resume:experience:$index") })
        val jobDescription = document(DocumentSourceType.JOB_DESCRIPTION, "jd-hash", "J".repeat(200),
            (0 until 3).map { index -> DocumentChunk(index, "Requirements", "Original JD requirement $index", "job_description:requirements:$index") })
        val resumeIndex = RagDocumentIndexHandle(UUID.randomUUID(), 1L)
        val jobIndex = RagDocumentIndexHandle(UUID.randomUUID(), 1L)
        Mockito.`when`(indexingService.ensureIndexed(resume)).thenReturn(Optional.of(resumeIndex))
        Mockito.`when`(indexingService.ensureIndexed(jobDescription)).thenReturn(Optional.of(jobIndex))
        Mockito.`when`(retrievalService.retrieve(anyString(), eq(resumeIndex), eq(6))).thenReturn(
            (0 until 6).map { index -> snippet("resume:experience:$index", "resume", "Experience", index, "Section: Experience\nResume evidence $index") })
        Mockito.`when`(retrievalService.retrieve(anyString(), eq(jobIndex), eq(4))).thenReturn(
            (0 until 3).map { index -> snippet("job_description:requirements:$index", "job_description", "Requirements", index, "Section: Requirements\nOriginal JD requirement $index") })

        val context = service.jobFitContext(CoachAnalysisInput(resume, Optional.of(jobDescription)))

        assertThat(context.sourceContextIds).contains(
            "job_description:requirements:0", "job_description:requirements:1", "job_description:requirements:2"
        )
        assertThat(context.context).contains("Original JD requirement 0").doesNotContain("Section: Requirements")
        Mockito.verify(retrievalService, Mockito.times(3)).retrieve(anyString(), eq(resumeIndex), eq(6))
        Mockito.verify(retrievalService, Mockito.times(3)).retrieve(anyString(), eq(jobIndex), eq(4))
    }

    @Test
    fun usesContextIdAsDeterministicRrfTieBreaker() {
        val indexingService = Mockito.mock(RagIndexingService::class.java)
        val retrievalService = Mockito.mock(RagRetrievalService::class.java)
        val service = service(indexingService, retrievalService)
        val resume = document(DocumentSourceType.RESUME, "resume-hash", "R".repeat(6_100), listOf(
            DocumentChunk(0, "Experience", "Alpha evidence", "resume:experience:0"),
            DocumentChunk(1, "Experience", "Beta evidence", "resume:experience:1")
        ))
        val index = RagDocumentIndexHandle(UUID.randomUUID(), 1L)
        Mockito.`when`(indexingService.ensureIndexed(resume)).thenReturn(Optional.of(index))
        val calls = AtomicInteger()
        Mockito.`when`(retrievalService.retrieve(anyString(), eq(index), eq(6))).thenAnswer {
            val alphaFirst = calls.getAndIncrement() % 2 == 0
            val alpha = snippet("resume:experience:0", "resume", "Experience", 0, "Alpha evidence")
            val beta = snippet("resume:experience:1", "resume", "Experience", 1, "Beta evidence")
            if (alphaFirst) listOf(alpha, beta) else listOf(beta, alpha)
        }

        // Six practice-question queries alternate the order, so both snippets tie on RRF score and best rank.
        val context = service.practiceQuestionContext(CoachAnalysisInput(resume, Optional.empty()))

        assertThat(context.sourceContextIds).startsWith("resume:experience:0", "resume:experience:1")
    }

    private fun service(indexingService: RagIndexingService, retrievalService: RagRetrievalService) = CoachRagContextService(
        SectionAwareTextChunker(), indexingService, retrievalService, SimpleMeterRegistry()
    )

    private fun snippet(contextId: String, source: String, section: String, index: Int, content: String) =
        RagContextSnippet("vector-$contextId", content, mapOf(
            "contextId" to contextId, "sourceType" to source, "section" to section, "chunkIndex" to index
        ), 0.9)

    private fun document(sourceType: DocumentSourceType, hash: String, text: String, chunks: List<DocumentChunk>) =
        ResolvedDocument(sourceType, UUID.randomUUID(), hash, text, chunks)
}
