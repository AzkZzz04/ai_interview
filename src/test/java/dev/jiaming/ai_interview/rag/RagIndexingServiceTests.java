package dev.jiaming.ai_interview.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import dev.jiaming.ai_interview.document.DocumentChunk;
import dev.jiaming.ai_interview.document.DocumentSourceType;
import dev.jiaming.ai_interview.document.ResolvedDocument;
import dev.jiaming.ai_interview.resume.SectionAwareTextChunker;

class RagIndexingServiceTests {

	private static final Instant NOW = Instant.parse("2026-07-20T12:00:00Z");

	private final VectorStore vectorStore = mock(VectorStore.class);

	@SuppressWarnings("unchecked")
	private final ObjectProvider<VectorStore> vectorStoreProvider = mock(ObjectProvider.class);

	private final RagDocumentIndexRepository repository = mock(RagDocumentIndexRepository.class);

	private final RagRetrievalService retrievalService = mock(RagRetrievalService.class);

	@Test
	void ownsNewClaimAndWritesFencedVectorMetadata() {
		when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
		when(repository.insertClaim(any(), any(), eq(NOW))).thenReturn(true);
		when(repository.markReady(any(), eq(1L), eq(1), eq(NOW))).thenReturn(true);
		RagIndexingService service = service();

		Optional<RagDocumentIndexHandle> result = service.ensureIndexed(document());

		assertThat(result).isPresent();
		ArgumentCaptor<List<Document>> documents = listCaptor();
		verify(vectorStore).add(documents.capture());
		Document stored = documents.getValue().getFirst();
		assertThat(stored.getMetadata())
			.containsEntry("indexId", result.get().indexId().toString())
			.containsEntry("claimVersion", 1L)
			.containsEntry("contextId", "resume:experience:0");
		assertThat(stored.getId()).isNotBlank();
	}

	@Test
	void staleClaimTakeoverCannotBeMarkedReadyByLostOwner() {
		when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
		RagDocumentIndexIdentity identity = identity();
		UUID indexId = UUID.randomUUID();
		RagDocumentIndex stale = new RagDocumentIndex(
			indexId,
			identity,
			RagDocumentIndexStatus.INDEXING,
			4L,
			NOW.minusSeconds(11 * 60),
			0,
			NOW.minusSeconds(11 * 60),
			NOW.minusSeconds(11 * 60)
		);
		when(repository.insertClaim(any(), any(), eq(NOW))).thenReturn(false);
		when(repository.find(any())).thenReturn(Optional.of(stale));
		when(repository.takeOver(eq(stale), eq(NOW), any(), any())).thenReturn(true);
		when(repository.markReady(indexId, 5L, 1, NOW)).thenReturn(false);

		Optional<RagDocumentIndexHandle> result = service().ensureIndexed(document());

		assertThat(result).isEmpty();
		verify(retrievalService).deleteIndex(indexId);
		verify(retrievalService).deleteIndexClaim(indexId, 5L);
	}

	@Test
	void freshIndexingClaimFallsBackWithoutDuplicateEmbedding() {
		when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
		UUID indexId = UUID.randomUUID();
		RagDocumentIndex fresh = new RagDocumentIndex(
			indexId,
			identity(),
			RagDocumentIndexStatus.INDEXING,
			2L,
			NOW.minusSeconds(30),
			0,
			NOW.minusSeconds(30),
			NOW.minusSeconds(30)
		);
		when(repository.insertClaim(any(), any(), eq(NOW))).thenReturn(false);
		when(repository.find(any())).thenReturn(Optional.of(fresh));

		assertThat(service().ensureIndexed(document())).isEmpty();
		verify(vectorStore, never()).add(any());
		verify(repository, never()).takeOver(any(), any(), any(), any());
	}

	@Test
	void readyDocumentIndexIsReusedWithoutASecondEmbeddingWrite() {
		when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
		when(repository.insertClaim(any(), any(), eq(NOW))).thenReturn(true, false);
		when(repository.markReady(any(), eq(1L), eq(1), eq(NOW))).thenReturn(true);
		RagIndexingService service = service();

		RagDocumentIndexHandle first = service.ensureIndexed(document()).orElseThrow();
		RagDocumentIndex ready = new RagDocumentIndex(
			first.indexId(),
			identity(),
			RagDocumentIndexStatus.READY,
			first.claimVersion(),
			NOW,
			1,
			NOW,
			NOW
		);
		when(repository.find(any())).thenReturn(Optional.of(ready));

		assertThat(service.ensureIndexed(document())).contains(first);
		verify(vectorStore, times(1)).add(any());
		verify(repository).touchReady(first.indexId(), first.claimVersion(), NOW);
	}

	@Test
	void terminalCleanupDeletesEveryClaimAndUsesAFencedDeleteClaim() {
		UUID indexId = UUID.randomUUID();
		RagDocumentIndex ready = new RagDocumentIndex(
			indexId,
			identity(),
			RagDocumentIndexStatus.READY,
			4L,
			NOW.minusSeconds(60),
			1,
			NOW.minusSeconds(8 * 24 * 60 * 60),
			NOW.minusSeconds(8 * 24 * 60 * 60)
		);
		when(repository.cleanupCandidates(any(), any(), eq(10))).thenReturn(List.of(ready));
		when(repository.claimDeleting(eq(ready), any(), any(), eq(NOW))).thenReturn(OptionalLong.of(5L));

		int deleted = service().cleanupStale(java.time.Duration.ofDays(7), 10);

		assertThat(deleted).isEqualTo(1);
		verify(retrievalService).deleteIndex(indexId);
		verify(repository).deleteClaimed(indexId, 5L);
	}

	private RagIndexingService service() {
		return new RagIndexingService(
			vectorStoreProvider,
			repository,
			retrievalService,
			new RagProperties(1_024, 8, "gemini-embedding-001", "section-context-v2"),
			new SectionAwareTextChunker(),
			new SimpleMeterRegistry(),
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
	}

	private ResolvedDocument document() {
		return new ResolvedDocument(
			DocumentSourceType.RESUME,
			UUID.randomUUID(),
			"resume-hash",
			"EXPERIENCE\nBuilt an API",
			List.of(new DocumentChunk(0, "Experience", "Built an API", "resume:experience:0"))
		);
	}

	private RagDocumentIndexIdentity identity() {
		return new RagDocumentIndexIdentity(
			DocumentSourceType.RESUME,
			"resume-hash",
			"gemini-embedding-001",
			1_024,
			"section-context-v2"
		);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private ArgumentCaptor<List<Document>> listCaptor() {
		return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
	}
}
