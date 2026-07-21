package dev.jiaming.ai_interview.rag;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import dev.jiaming.ai_interview.document.DocumentChunk;
import dev.jiaming.ai_interview.document.ResolvedDocument;
import dev.jiaming.ai_interview.resume.SectionAwareTextChunker;

@Service
public class RagIndexingService {

	private static final Logger log = LoggerFactory.getLogger(RagIndexingService.class);

	private static final Duration STALE_INDEXING = Duration.ofMinutes(10);

	private static final Duration FAILED_RETRY = Duration.ofMinutes(5);

	private static final Duration STALE_DELETING = Duration.ofMinutes(10);

	private final ObjectProvider<VectorStore> vectorStoreProvider;

	private final RagDocumentIndexRepository repository;

	private final RagRetrievalService retrievalService;

	private final RagProperties properties;

	private final String embeddingModel;

	private final SectionAwareTextChunker chunker;

	private final MeterRegistry meterRegistry;

	private final Clock clock;

	@Autowired
	public RagIndexingService(
		ObjectProvider<VectorStore> vectorStoreProvider,
		RagDocumentIndexRepository repository,
		RagRetrievalService retrievalService,
		RagProperties properties,
		SectionAwareTextChunker chunker,
		MeterRegistry meterRegistry,
		Environment environment
	) {
		this(
			vectorStoreProvider,
			repository,
			retrievalService,
			properties,
			chunker,
			meterRegistry,
			Clock.systemUTC(),
			environment.getProperty(
				"spring.ai.google.genai.embedding.text.options.model",
				properties.embeddingModel()
			)
		);
	}

	RagIndexingService(
		ObjectProvider<VectorStore> vectorStoreProvider,
		RagDocumentIndexRepository repository,
		RagRetrievalService retrievalService,
		RagProperties properties,
		SectionAwareTextChunker chunker,
		MeterRegistry meterRegistry,
		Clock clock
	) {
		this(
			vectorStoreProvider,
			repository,
			retrievalService,
			properties,
			chunker,
			meterRegistry,
			clock,
			properties.embeddingModel()
		);
	}

	RagIndexingService(
		ObjectProvider<VectorStore> vectorStoreProvider,
		RagDocumentIndexRepository repository,
		RagRetrievalService retrievalService,
		RagProperties properties,
		SectionAwareTextChunker chunker,
		MeterRegistry meterRegistry,
		Clock clock,
		String embeddingModel
	) {
		this.vectorStoreProvider = vectorStoreProvider;
		this.repository = repository;
		this.retrievalService = retrievalService;
		this.properties = properties;
		this.embeddingModel = embeddingModel;
		this.chunker = chunker;
		this.meterRegistry = meterRegistry;
		this.clock = clock;
	}

	public Optional<RagDocumentIndexHandle> ensureIndexed(ResolvedDocument document) {
		if (vectorStoreProvider.getIfAvailable() == null) {
			return Optional.empty();
		}

		RagDocumentIndexIdentity identity = new RagDocumentIndexIdentity(
			document.sourceType(),
			document.contentHash(),
			embeddingModel,
			properties.embeddingDimensions(),
			properties.chunkSchema()
		);
		Instant now = clock.instant();
		UUID candidateId = UUID.randomUUID();
		if (repository.insertClaim(candidateId, identity, now)) {
			record("created", document);
			return indexOwnedClaim(document, candidateId, 1L);
		}

		Optional<RagDocumentIndex> found = repository.find(identity);
		if (found.isEmpty()) {
			return Optional.empty();
		}

		RagDocumentIndex current = found.get();
		if (current.status() == RagDocumentIndexStatus.READY) {
			repository.touchReady(current.indexId(), current.claimVersion(), now);
			record("reused", document);
			return Optional.of(new RagDocumentIndexHandle(current.indexId(), current.claimVersion()));
		}

		if (eligibleForTakeover(current, now)
			&& repository.takeOver(current, now, now.minus(STALE_INDEXING), now.minus(FAILED_RETRY))) {
			long claimVersion = current.claimVersion() + 1;
			deletePreviousClaims(current.indexId());
			record("takeover", document);
			return indexOwnedClaim(document, current.indexId(), claimVersion);
		}

		record("local_fallback", document);
		return Optional.empty();
	}

	public int cleanupStale(Duration retention, int limit) {
		Instant now = clock.instant();
		Instant cutoff = now.minus(retention);
		Instant deletingCutoff = now.minus(STALE_DELETING);
		int deleted = 0;
		for (RagDocumentIndex index : repository.cleanupCandidates(cutoff, deletingCutoff, limit)) {
			OptionalLong cleanupClaim = repository.claimDeleting(index, cutoff, deletingCutoff, now);
			if (cleanupClaim.isEmpty()) {
				continue;
			}
			long claimVersion = cleanupClaim.getAsLong();
			try {
				retrievalService.deleteIndex(index.indexId());
				repository.deleteClaimed(index.indexId(), claimVersion);
				deleted++;
			}
			catch (RuntimeException exception) {
				repository.restoreDeleteFailure(index.indexId(), claimVersion, clock.instant());
				log.warn(
					"rag_index_cleanup_failed indexId={} claimVersion={}",
					index.indexId(),
					claimVersion
				);
			}
		}
		meterRegistry.counter("ai.rag.cleanup", "outcome", "deleted").increment(deleted);
		return deleted;
	}

	private Optional<RagDocumentIndexHandle> indexOwnedClaim(
		ResolvedDocument resolvedDocument,
		UUID indexId,
		long claimVersion
	) {
		List<DocumentChunk> chunks = chunks(resolvedDocument);
		List<Document> documents = documents(resolvedDocument, chunks, indexId, claimVersion);
		try {
			VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
			if (vectorStore == null) {
				markFailed(indexId, claimVersion, "VECTOR_STORE_UNAVAILABLE", resolvedDocument);
				return Optional.empty();
			}
			if (!documents.isEmpty()) {
				vectorStore.delete(documents.stream().map(Document::getId).toList());
				vectorStore.add(documents);
			}

			if (!repository.markReady(indexId, claimVersion, documents.size(), clock.instant())) {
				retrievalService.deleteIndexClaim(indexId, claimVersion);
				record("claim_lost", resolvedDocument);
				log.info("rag_index_claim_lost indexId={} claimVersion={}", indexId, claimVersion);
				return Optional.empty();
			}

			record("ready", resolvedDocument);
			log.info(
				"rag_index_ready indexId={} claimVersion={} sourceType={} documents={} model={}",
				indexId,
				claimVersion,
				resolvedDocument.sourceType(),
				documents.size(),
				embeddingModel
			);
			return Optional.of(new RagDocumentIndexHandle(indexId, claimVersion));
		}
		catch (RuntimeException exception) {
			try {
				markFailed(indexId, claimVersion, "VECTOR_INDEX_FAILED", resolvedDocument);
				retrievalService.deleteIndexClaim(indexId, claimVersion);
			}
			catch (RuntimeException cleanupException) {
				log.warn("rag_index_failure_cleanup_failed indexId={} claimVersion={}", indexId, claimVersion);
			}
			log.warn(
				"rag_index_failed indexId={} claimVersion={} sourceType={}",
				indexId,
				claimVersion,
				resolvedDocument.sourceType()
			);
			return Optional.empty();
		}
	}

	private void markFailed(
		UUID indexId,
		long claimVersion,
		String errorCode,
		ResolvedDocument document
	) {
		if (!repository.markFailed(indexId, claimVersion, errorCode, clock.instant())) {
			record("claim_lost", document);
			return;
		}
		record("failed", document);
	}

	private void deletePreviousClaims(UUID indexId) {
		try {
			retrievalService.deleteIndex(indexId);
		}
		catch (RuntimeException exception) {
			log.warn("rag_index_takeover_cleanup_failed indexId={}", indexId);
		}
	}

	private boolean eligibleForTakeover(RagDocumentIndex index, Instant now) {
		if (index.status() == RagDocumentIndexStatus.INDEXING) {
			return index.indexingStartedAt() == null || index.indexingStartedAt().isBefore(now.minus(STALE_INDEXING));
		}
		return index.status() == RagDocumentIndexStatus.FAILED
			&& index.updatedAt().isBefore(now.minus(FAILED_RETRY));
	}

	private List<DocumentChunk> chunks(ResolvedDocument document) {
		if (!document.persistedChunks().isEmpty()) {
			return document.persistedChunks();
		}
		return chunker.chunk(document.normalizedText()).stream()
			.map(chunk -> new DocumentChunk(
				chunk.index(),
				chunk.section(),
				chunk.content(),
				RagContextId.forChunk(document.sourceType().metadataValue(), chunk)
			))
			.toList();
	}

	private List<Document> documents(
		ResolvedDocument resolvedDocument,
		List<DocumentChunk> chunks,
		UUID indexId,
		long claimVersion
	) {
		List<Document> documents = new ArrayList<>();
		for (DocumentChunk chunk : chunks) {
			String contextId = blank(chunk.contextId())
				? RagContextId.forChunk(resolvedDocument.sourceType().metadataValue(), chunk.section(), chunk.index())
				: chunk.contextId();
			String documentId = documentId(indexId, claimVersion, contextId);
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("indexId", indexId.toString());
			metadata.put("claimVersion", claimVersion);
			metadata.put("contextId", contextId);
			metadata.put("sourceType", resolvedDocument.sourceType().metadataValue());
			metadata.put("section", chunk.section());
			metadata.put("chunkIndex", chunk.index());
			documents.add(new Document(documentId, chunk.content(), metadata));
		}
		return documents;
	}

	private String documentId(UUID indexId, long claimVersion, String contextId) {
		return UUID.nameUUIDFromBytes(
			(indexId + ":" + claimVersion + ":" + contextId).getBytes(StandardCharsets.UTF_8)
		).toString();
	}

	private void record(String outcome, ResolvedDocument document) {
		meterRegistry.counter(
			"ai.rag.index",
			"outcome", outcome,
			"source", document.sourceType().metadataValue()
		).increment();
	}

	private boolean blank(String value) {
		return value == null || value.isBlank();
	}
}
