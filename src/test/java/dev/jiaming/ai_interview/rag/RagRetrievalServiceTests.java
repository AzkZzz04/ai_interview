package dev.jiaming.ai_interview.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

class RagRetrievalServiceTests {

	@Test
	@SuppressWarnings("unchecked")
	void retrievalFilterContainsEachIndexAndItsClaimVersion() {
		VectorStore vectorStore = mock(VectorStore.class);
		ObjectProvider<VectorStore> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(vectorStore);
		when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
			new Document(
				"doc",
				"context",
				Map.of("contextId", "resume:experience:0", "indexId", "current", "claimVersion", 4L)
			)
		));
		RagRetrievalService service = new RagRetrievalService(
			provider,
			new RagProperties(1_024, 8, "gemini-embedding-001", "section-block-v3"),
			new SimpleMeterRegistry()
		);
		UUID resumeIndex = UUID.randomUUID();
		UUID jobIndex = UUID.randomUUID();

		List<RagContextSnippet> result = service.retrieve(
			"backend engineering",
			List.of(new RagDocumentIndexHandle(resumeIndex, 4L), new RagDocumentIndexHandle(jobIndex, 2L)),
			6
		);

		assertThat(result).extracting(RagContextSnippet::sourceContextId)
			.containsExactly("resume:experience:0");
		ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
		verify(vectorStore).similaritySearch(request.capture());
		String filter = request.getValue().getFilterExpression().toString();
		assertThat(filter)
			.contains(resumeIndex.toString(), jobIndex.toString(), "claimVersion", "4", "2");
	}
}
