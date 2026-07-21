package dev.jiaming.ai_interview.coach;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.jiaming.ai_interview.document.DocumentChunk;
import dev.jiaming.ai_interview.document.ResolvedDocument;
import dev.jiaming.ai_interview.rag.RagContextId;
import dev.jiaming.ai_interview.rag.RagContextSnippet;
import dev.jiaming.ai_interview.rag.RagDocumentIndexHandle;
import dev.jiaming.ai_interview.rag.RagIndexingService;
import dev.jiaming.ai_interview.rag.RagRetrievalService;
import dev.jiaming.ai_interview.resume.SectionAwareTextChunker;

@Service
public class CoachRagContextService {

	private static final Logger log = LoggerFactory.getLogger(CoachRagContextService.class);

	private static final int DIRECT_CONTEXT_LIMIT = 6_000;

	private static final int RAG_TOP_K_PER_QUERY = 4;

	private final SectionAwareTextChunker chunker;

	private final RagIndexingService indexingService;

	private final RagRetrievalService retrievalService;

	private final MeterRegistry meterRegistry;

	public CoachRagContextService(
		SectionAwareTextChunker chunker,
		RagIndexingService indexingService,
		RagRetrievalService retrievalService,
		MeterRegistry meterRegistry
	) {
		this.chunker = chunker;
		this.indexingService = indexingService;
		this.retrievalService = retrievalService;
		this.meterRegistry = meterRegistry;
	}

	public CoachRagContext assessmentContext(CoachAnalysisInput input) {
		return ragContext(input.resume(), input.jobDescription(), assessmentQueries(input), 14);
	}

	public CoachRagContext questionContext(CoachAnalysisInput input) {
		return ragContext(input.resume(), input.jobDescription(), questionQueries(input), 16);
	}

	public CoachRagContext feedbackContext(CoachFeedbackInput input) {
		return ragContext(input.resume(), input.jobDescription(), feedbackQueries(input), 10);
	}

	private CoachRagContext ragContext(
		ResolvedDocument resume,
		Optional<ResolvedDocument> jobDescription,
		List<String> queries,
		int maxSnippets
	) {
		List<ResolvedDocument> documents = new ArrayList<>();
		documents.add(resume);
		jobDescription.ifPresent(documents::add);

		if (documents.stream().mapToInt(document -> safe(document.normalizedText()).length()).sum() <= DIRECT_CONTEXT_LIMIT) {
			List<RagContextSnippet> snippets = documents.stream().flatMap(document -> localSnippets(document).stream()).toList();
			meterRegistry.counter("ai.rag.context", "mode", "direct").increment();
			log.info("rag_context_ready mode=direct snippets={}", snippets.size());
			return context("direct-context", snippets, Integer.MAX_VALUE, false, false);
		}

		List<RagDocumentIndexHandle> readyIndexes = new ArrayList<>();
		List<ResolvedDocument> localDocuments = new ArrayList<>();
		for (ResolvedDocument document : documents) {
			try {
				Optional<RagDocumentIndexHandle> handle = indexingService.ensureIndexed(document);
				if (handle.isPresent()) {
					readyIndexes.add(handle.get());
				}
				else {
					localDocuments.add(document);
				}
			}
			catch (RuntimeException exception) {
				localDocuments.add(document);
				log.warn("rag_index_fallback sourceType={}", document.sourceType());
			}
		}

		Map<String, RagContextSnippet> snippetsByContextId = new LinkedHashMap<>();
		int localPerDocumentLimit = localDocuments.isEmpty()
			? 0
			: Math.max(1, maxSnippets / Math.max(readyIndexes.isEmpty() ? localDocuments.size() : documents.size(), 1));
		addLocalSnippets(snippetsByContextId, localDocuments, maxSnippets, localPerDocumentLimit);
		boolean retrievalAdded = false;
		boolean retrievalFailed = false;
		if (!readyIndexes.isEmpty()) {
			try {
				for (String query : queries) {
					String retrievalQuery = safe(query).trim();
					if (retrievalQuery.isEmpty()) {
						continue;
					}
					for (RagContextSnippet snippet : retrievalService.retrieve(
						retrievalQuery,
						readyIndexes,
						RAG_TOP_K_PER_QUERY
					)) {
							if (snippetsByContextId.putIfAbsent(snippet.sourceContextId(), snippet) == null) {
								retrievalAdded = true;
							}
						if (snippetsByContextId.size() >= maxSnippets) {
							break;
						}
					}
					if (snippetsByContextId.size() >= maxSnippets) {
						break;
					}
				}
			}
			catch (RuntimeException exception) {
				log.warn("rag_retrieval_fallback indexes={}", readyIndexes.size());
				retrievalFailed = true;
			}
		}

		if (retrievalFailed || (!readyIndexes.isEmpty() && !retrievalAdded)) {
			snippetsByContextId.clear();
			int perDocumentLimit = Math.max(1, maxSnippets / Math.max(documents.size(), 1));
			addLocalSnippets(snippetsByContextId, documents, maxSnippets, perDocumentLimit);
			retrievalAdded = false;
		}
		else if (snippetsByContextId.isEmpty()) {
			addLocalSnippets(snippetsByContextId, documents, maxSnippets, maxSnippets);
		}

		List<RagContextSnippet> snippets = new ArrayList<>(snippetsByContextId.values());
		boolean vectorBacked = retrievalAdded;
		meterRegistry.counter("ai.rag.context", "mode", vectorBacked ? "retrieval" : "local").increment();
		log.info(
			"rag_context_ready mode={} indexes={} snippets={}",
			vectorBacked ? "retrieval" : "local",
			readyIndexes.size(),
			snippets.size()
		);
		return context("document-indexes", snippets, maxSnippets, vectorBacked, true);
	}

	private CoachRagContext context(
		String contextId,
		List<RagContextSnippet> snippets,
		int maxSnippets,
		boolean vectorBacked,
		boolean truncateContent
	) {
		return new CoachRagContext(
			contextId,
			formatSnippets(snippets, maxSnippets, truncateContent),
			snippets.stream()
				.map(RagContextSnippet::sourceContextId)
				.filter(value -> !blank(value))
				.distinct()
				.toList(),
			vectorBacked
		);
	}

	private List<String> assessmentQueries(CoachAnalysisInput input) {
		String role = fallback(input.targetRole(), "Software Engineer");
		String seniority = fallback(input.seniority(), "Mid-level");
		String jobDescription = jobDescriptionQueryExcerpt(input.jobDescription());
		return List.of(
			"technical depth systems ownership architecture complexity " + role + " " + seniority,
			"measurable impact metrics scale latency reliability cost adoption outcomes",
			"role alignment required skills must have requirements " + role + " " + jobDescription,
			"resume gaps missing evidence weak bullets seniority signal " + role + " " + seniority
		);
	}

	private List<String> questionQueries(CoachAnalysisInput input) {
		String role = fallback(input.targetRole(), "Software Engineer");
		String seniority = fallback(input.seniority(), "Mid-level");
		String jobDescription = jobDescriptionQueryExcerpt(input.jobDescription());
		return List.of(
			"strongest projects ownership technical complexity " + role + " " + seniority,
			"weakest resume areas missing detail interview probe " + role,
			"system design architecture scaling data flow production tradeoffs",
			"debugging incident response observability database cache production",
			"collaboration leadership stakeholder tradeoff communication",
			"job description requirements role specific tooling " + jobDescription
		);
	}

	private List<String> feedbackQueries(CoachFeedbackInput input) {
		return List.of(
			fallback(input.questionText(), ""),
			String.join(" ", input.expectedSignals()),
			fallback(input.category(), "") + " " + fallback(input.targetRole(), ""),
			"source experience and project context expected evidence answer evaluation"
		);
	}

	private List<RagContextSnippet> localSnippets(ResolvedDocument document) {
		List<DocumentChunk> chunks = document.persistedChunks().isEmpty()
			? chunker.chunk(document.normalizedText()).stream()
				.map(chunk -> new DocumentChunk(
					chunk.index(),
					chunk.section(),
					chunk.content(),
					RagContextId.forChunk(document.sourceType().metadataValue(), chunk)
				))
				.toList()
			: document.persistedChunks();

		return chunks.stream().map(chunk -> {
			String contextId = blank(chunk.contextId())
				? RagContextId.forChunk(document.sourceType().metadataValue(), chunk.section(), chunk.index())
				: chunk.contextId();
			return new RagContextSnippet(
				"local-" + document.sourceType().metadataValue() + "-" + chunk.index(),
				chunk.content(),
				Map.of(
					"contextId", contextId,
					"sourceType", document.sourceType().metadataValue(),
					"section", fallback(chunk.section(), "section"),
					"chunkIndex", chunk.index()
				),
				null
			);
		}).toList();
	}

	private void addLocalSnippets(
		Map<String, RagContextSnippet> snippetsByContextId,
		List<ResolvedDocument> documents,
		int maxSnippets,
		int perDocumentLimit
	) {
		for (ResolvedDocument document : documents) {
			int addedForDocument = 0;
			for (RagContextSnippet snippet : localSnippets(document)) {
				if (snippetsByContextId.putIfAbsent(snippet.sourceContextId(), snippet) == null) {
					addedForDocument++;
				}
				if (snippetsByContextId.size() >= maxSnippets || addedForDocument >= perDocumentLimit) {
					break;
				}
			}
			if (snippetsByContextId.size() >= maxSnippets) {
				break;
			}
		}
	}

	private String jobDescriptionQueryExcerpt(Optional<ResolvedDocument> jobDescription) {
		return jobDescription
			.map(ResolvedDocument::normalizedText)
			.map(text -> truncate(text, 1_000).replace('\n', ' '))
			.orElse("");
	}

	private String formatSnippets(List<RagContextSnippet> snippets, int maxSnippets, boolean truncateContent) {
		if (snippets.isEmpty()) {
			return "No retrieved context was available.";
		}
		return snippets.stream()
			.limit(maxSnippets)
			.map(snippet -> formatSnippet(snippet, truncateContent))
			.reduce((left, right) -> left + "\n\n" + right)
			.orElse("No retrieved context was available.");
	}

	private String formatSnippet(RagContextSnippet snippet, boolean truncateContent) {
		Map<String, Object> metadata = snippet.metadata() == null ? Map.of() : snippet.metadata();
		return """
			[contextId=%s source=%s section=%s score=%s]
			%s
			""".formatted(
			snippet.sourceContextId(),
			metadataValue(metadata, "sourceType"),
			metadataValue(metadata, "section"),
			snippet.score() == null ? "n/a" : "%.4f".formatted(snippet.score()),
			truncateContent ? truncate(snippet.content(), 1_200) : safe(snippet.content())
		).trim();
	}

	private String truncate(String value, int limit) {
		String safeValue = safe(value);
		return safeValue.length() <= limit ? safeValue : safeValue.substring(0, limit) + "\n[truncated]";
	}

	private String fallback(String value, String fallback) {
		return blank(value) ? fallback : value.trim();
	}

	private String safe(String value) {
		return value == null ? "" : value;
	}

	private boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private String metadataValue(Map<String, Object> metadata, String key) {
		Object value = metadata.get(key);
		return value == null ? "unknown" : value.toString();
	}
}
