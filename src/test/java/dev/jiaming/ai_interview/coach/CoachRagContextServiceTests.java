package dev.jiaming.ai_interview.coach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import dev.jiaming.ai_interview.document.DocumentChunk;
import dev.jiaming.ai_interview.document.DocumentSourceType;
import dev.jiaming.ai_interview.document.ResolvedDocument;
import dev.jiaming.ai_interview.rag.RagIndexingService;
import dev.jiaming.ai_interview.rag.RagContextSnippet;
import dev.jiaming.ai_interview.rag.RagDocumentIndexHandle;
import dev.jiaming.ai_interview.rag.RagRetrievalService;
import dev.jiaming.ai_interview.resume.SectionAwareTextChunker;

class CoachRagContextServiceTests {

	@Test
	void shortDocumentsUseEveryPersistedChunkWithoutEmbedding() {
		RagIndexingService indexingService = mock(RagIndexingService.class);
		RagRetrievalService retrievalService = mock(RagRetrievalService.class);
		CoachRagContextService service = new CoachRagContextService(
			new SectionAwareTextChunker(),
			indexingService,
			retrievalService,
			new SimpleMeterRegistry()
		);
		ResolvedDocument resume = document(
			DocumentSourceType.RESUME,
			"resume-hash",
			"short resume",
			List.of(
				new DocumentChunk(0, "Experience", "Built an API", "resume:experience:0"),
				new DocumentChunk(1, "Projects", "Built an AI coach", "resume:projects:1")
			)
		);
		ResolvedDocument jobDescription = document(
			DocumentSourceType.JOB_DESCRIPTION,
			"jd-hash",
			"short job description",
			List.of(new DocumentChunk(0, "Requirements", "Java required", "job_description:requirements:0"))
		);

		CoachRagContext context = service.assessmentContext(new CoachAnalysisInput(
			resume,
			Optional.of(jobDescription),
			"Backend Engineer",
			"Mid-level"
		));

		assertThat(context.vectorBacked()).isFalse();
		assertThat(context.context()).contains("Built an API", "Built an AI coach", "Java required");
		assertThat(context.sourceContextIds()).containsExactly(
			"resume:experience:0",
			"resume:projects:1",
			"job_description:requirements:0"
		);
		verifyNoInteractions(indexingService, retrievalService);
	}

	@Test
	void localDocumentContextIsReservedWhenAnotherDocumentUsesVectorRetrieval() {
		RagIndexingService indexingService = mock(RagIndexingService.class);
		RagRetrievalService retrievalService = mock(RagRetrievalService.class);
		CoachRagContextService service = new CoachRagContextService(
			new SectionAwareTextChunker(),
			indexingService,
			retrievalService,
			new SimpleMeterRegistry()
		);
		ResolvedDocument resume = document(
			DocumentSourceType.RESUME,
			"resume-hash",
			"R".repeat(6_100),
			List.of(new DocumentChunk(0, "Experience", "Resume experience", "resume:experience:0"))
		);
		ResolvedDocument jobDescription = document(
			DocumentSourceType.JOB_DESCRIPTION,
			"jd-hash",
			"Java and distributed systems are required",
			List.of(new DocumentChunk(
				0,
				"Requirements",
				"Java and distributed systems are required",
				"job_description:requirements:0"
			))
		);
		RagDocumentIndexHandle handle = new RagDocumentIndexHandle(UUID.randomUUID(), 1L);
		when(indexingService.ensureIndexed(resume)).thenReturn(Optional.of(handle));
		when(indexingService.ensureIndexed(jobDescription)).thenReturn(Optional.empty());
		when(retrievalService.retrieve(anyString(), anyList(), eq(4))).thenReturn(
			java.util.stream.IntStream.range(0, 14)
				.mapToObj(index -> new RagContextSnippet(
					"resume-vector-" + index,
					"Resume vector context " + index,
					java.util.Map.of(
						"contextId", "resume:experience:" + index,
						"sourceType", "resume",
						"section", "Experience"
					),
					0.9
				))
				.toList()
		);

		CoachRagContext context = service.assessmentContext(new CoachAnalysisInput(
			resume,
			Optional.of(jobDescription),
			"Backend Engineer",
			"Mid-level"
		));

		assertThat(context.vectorBacked()).isTrue();
		assertThat(context.context()).contains("Java and distributed systems are required");
		assertThat(context.sourceContextIds()).contains("job_description:requirements:0");
	}

	@Test
	void retrievalFailureUsesLocalContextAndIsNotReportedAsVectorBacked() {
		RagIndexingService indexingService = mock(RagIndexingService.class);
		RagRetrievalService retrievalService = mock(RagRetrievalService.class);
		CoachRagContextService service = new CoachRagContextService(
			new SectionAwareTextChunker(),
			indexingService,
			retrievalService,
			new SimpleMeterRegistry()
		);
		ResolvedDocument resume = document(
			DocumentSourceType.RESUME,
			"resume-hash",
			"R".repeat(6_100),
			List.of(new DocumentChunk(0, "Experience", "Resume experience", "resume:experience:0"))
		);
		RagDocumentIndexHandle handle = new RagDocumentIndexHandle(UUID.randomUUID(), 1L);
		when(indexingService.ensureIndexed(resume)).thenReturn(Optional.of(handle));
		when(retrievalService.retrieve(anyString(), anyList(), eq(4)))
			.thenThrow(new IllegalStateException("vector store unavailable"));

		CoachRagContext context = service.assessmentContext(new CoachAnalysisInput(
			resume,
			Optional.empty(),
			"Backend Engineer",
			"Mid-level"
		));

		assertThat(context.vectorBacked()).isFalse();
		assertThat(context.context()).contains("Resume experience");
		assertThat(context.sourceContextIds()).containsExactly("resume:experience:0");
	}

	private ResolvedDocument document(
		DocumentSourceType sourceType,
		String hash,
		String text,
		List<DocumentChunk> chunks
	) {
		return new ResolvedDocument(sourceType, UUID.randomUUID(), hash, text, chunks);
	}
}
