package dev.jiaming.ai_interview.rag;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter.Expression;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class RagRetrievalService {

	private final ObjectProvider<VectorStore> vectorStoreProvider;

	private final RagProperties ragProperties;

	private final MeterRegistry meterRegistry;

	public RagRetrievalService(
		ObjectProvider<VectorStore> vectorStoreProvider,
		RagProperties ragProperties,
		MeterRegistry meterRegistry
	) {
		this.vectorStoreProvider = vectorStoreProvider;
		this.ragProperties = ragProperties;
		this.meterRegistry = meterRegistry;
	}

	public List<RagContextSnippet> retrieve(String query) {
		return retrieve(query, ragProperties.defaultTopK());
	}

	public List<RagContextSnippet> retrieve(String query, int topK) {
		String retrievalQuery = normalizedQuery(query);
		if (retrievalQuery.isEmpty()) {
			return List.of();
		}
		SearchRequest request = SearchRequest.builder().query(retrievalQuery).topK(topK).build();
		return search(request);
	}

	public List<RagContextSnippet> retrieve(
		String query,
		List<RagDocumentIndexHandle> indexes,
		int topK
	) {
		String retrievalQuery = normalizedQuery(query);
		if (retrievalQuery.isEmpty() || indexes == null || indexes.isEmpty()) {
			return List.of();
		}
		SearchRequest request = SearchRequest.builder()
			.query(retrievalQuery)
			.topK(topK)
			.filterExpression(filter(indexes))
			.build();
		return search(request);
	}

	public List<RagContextSnippet> retrieve(String query, RagDocumentIndexHandle index, int topK) {
		if (index == null) {
			return List.of();
		}
		return retrieve(query, List.of(index), topK);
	}

	public void deleteIndexClaim(UUID indexId, long claimVersion) {
		FilterExpressionBuilder builder = new FilterExpressionBuilder();
		vectorStore().delete(builder.and(
			builder.eq("indexId", indexId.toString()),
			builder.eq("claimVersion", claimVersion)
		).build());
	}

	public void deleteIndex(UUID indexId) {
		FilterExpressionBuilder builder = new FilterExpressionBuilder();
		vectorStore().delete(builder.eq("indexId", indexId.toString()).build());
	}

	private List<RagContextSnippet> search(SearchRequest request) {
		long startedAt = System.nanoTime();
		try {
			List<RagContextSnippet> snippets = vectorStore().similaritySearch(request).stream()
				.map(this::toSnippet)
				.toList();
			meterRegistry.counter("ai.rag.retrieval", "outcome", "success").increment();
			return snippets;
		}
		catch (RuntimeException exception) {
			meterRegistry.counter("ai.rag.retrieval", "outcome", "failed").increment();
			throw exception;
		}
		finally {
			meterRegistry.timer("ai.rag.retrieval.duration")
				.record(Duration.ofMillis(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)));
		}
	}

	private RagContextSnippet toSnippet(Document document) {
		return new RagContextSnippet(document.getId(), document.getText(), document.getMetadata(), document.getScore());
	}

	private Expression filter(List<RagDocumentIndexHandle> indexes) {
		FilterExpressionBuilder builder = new FilterExpressionBuilder();
		FilterExpressionBuilder.Op combined = null;
		for (RagDocumentIndexHandle index : indexes) {
			FilterExpressionBuilder.Op current = builder.and(
				builder.eq("indexId", index.indexId().toString()),
				builder.eq("claimVersion", index.claimVersion())
			);
			combined = combined == null ? current : builder.or(combined, current);
		}
		return combined.build();
	}

	private String normalizedQuery(String query) {
		return query == null ? "" : query.trim();
	}

	private VectorStore vectorStore() {
		VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
		if (vectorStore == null) {
			throw new IllegalStateException("VectorStore is not available");
		}
		return vectorStore;
	}
}
