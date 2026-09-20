package dev.jiaming.ai_interview.rag

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.ObjectProvider

class RagRetrievalServiceTests {
	@Test
	@Suppress("UNCHECKED_CAST")
	fun retrievalFilterContainsEachIndexAndItsClaimVersion() {
		val vectorStore = Mockito.mock(VectorStore::class.java)
		val provider = Mockito.mock(ObjectProvider::class.java) as ObjectProvider<VectorStore>
		Mockito.`when`(provider.ifAvailable).thenReturn(vectorStore)
		Mockito.`when`(vectorStore.similaritySearch(any(SearchRequest::class.java))).thenReturn(listOf(Document("doc", "context", mapOf<String, Any>("contextId" to "resume:experience:0", "indexId" to "current", "claimVersion" to 4L))))
		val service = RagRetrievalService(provider, RagProperties(1_024, 8, "gemini-embedding-001", "section-block-v3"), SimpleMeterRegistry())
		val resumeIndex = UUID.randomUUID()
		val jobIndex = UUID.randomUUID()

		val result = service.retrieve("backend engineering", listOf(RagDocumentIndexHandle(resumeIndex, 4L), RagDocumentIndexHandle(jobIndex, 2L)), 6)

		assertThat(result.map { it.sourceContextId() }).containsExactly("resume:experience:0")
		val request = ArgumentCaptor.forClass(SearchRequest::class.java)
		Mockito.verify(vectorStore).similaritySearch(request.capture())
		val filter = request.value.filterExpression.toString()
		assertThat(filter).contains(resumeIndex.toString(), jobIndex.toString(), "claimVersion", "4", "2")
	}
}
