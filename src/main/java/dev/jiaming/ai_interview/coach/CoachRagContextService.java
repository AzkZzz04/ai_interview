package dev.jiaming.ai_interview.coach;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import dev.jiaming.ai_interview.document.DocumentChunk;
import dev.jiaming.ai_interview.document.DocumentSourceType;
import dev.jiaming.ai_interview.document.ResolvedDocument;
import dev.jiaming.ai_interview.rag.RagContextId;
import dev.jiaming.ai_interview.rag.RagContextSnippet;
import dev.jiaming.ai_interview.rag.RagDocumentIndexHandle;
import dev.jiaming.ai_interview.rag.RagIndexingService;
import dev.jiaming.ai_interview.rag.RagProperties;
import dev.jiaming.ai_interview.rag.RagRetrievalService;
import dev.jiaming.ai_interview.resume.SectionAwareTextChunker;

@Service
public class CoachRagContextService {

	private static final Logger log = LoggerFactory.getLogger(CoachRagContextService.class);

	private static final int DIRECT_CONTEXT_LIMIT = 6_000;

	private final SectionAwareTextChunker chunker;

	private final RagIndexingService indexingService;

	private final RagRetrievalService retrievalService;

	private final MeterRegistry meterRegistry;

	private final RagProperties properties;

	@Autowired
	public CoachRagContextService(
		SectionAwareTextChunker chunker,
		RagIndexingService indexingService,
		RagRetrievalService retrievalService,
		MeterRegistry meterRegistry,
		RagProperties properties
	) {
		this.chunker = chunker;
		this.indexingService = indexingService;
		this.retrievalService = retrievalService;
		this.meterRegistry = meterRegistry;
		this.properties = properties;
	}

	CoachRagContextService(
		SectionAwareTextChunker chunker,
		RagIndexingService indexingService,
		RagRetrievalService retrievalService,
		MeterRegistry meterRegistry
	) {
		this(chunker, indexingService, retrievalService, meterRegistry,
			new RagProperties(1_024, 8, "gemini-embedding-001", "section-block-v3"));
	}

	public CoachRagContext assessmentContext(CoachAnalysisInput input) {
		return ragContext(
			input.resume(), input.jobDescription(), assessmentQueries(input),
			new SelectionProfile("assessment", properties.assessmentContextBudget(),
				properties.assessmentJobDescriptionMinimum())
		);
	}

	public CoachRagContext questionContext(CoachAnalysisInput input) {
		return ragContext(
			input.resume(), input.jobDescription(), questionQueries(input),
			new SelectionProfile("questions", properties.questionContextBudget(),
				properties.questionJobDescriptionMinimum())
		);
	}

	public CoachRagContext feedbackContext(CoachFeedbackInput input) {
		return ragContext(
			input.resume(), input.jobDescription(), feedbackQueries(input),
			new SelectionProfile("feedback", properties.feedbackContextBudget(),
				properties.feedbackJobDescriptionMinimum())
		);
	}

	private CoachRagContext ragContext(
		ResolvedDocument resume,
		Optional<ResolvedDocument> jobDescription,
		List<String> queries,
		SelectionProfile profile
	) {
		List<ResolvedDocument> documents = new ArrayList<>();
		documents.add(resume);
		jobDescription.ifPresent(documents::add);

		if (documents.stream().mapToInt(document -> safe(document.normalizedText()).length()).sum() <= DIRECT_CONTEXT_LIMIT) {
			List<RagContextSnippet> snippets = documents.stream()
				.flatMap(document -> localSnippets(document).stream())
				.toList();
			meterRegistry.counter("ai.rag.context", "mode", "direct", "workflow", profile.name()).increment();
			return context("direct-context", snippets, Integer.MAX_VALUE, false, false);
		}

		Map<DocumentSourceType, IndexedDocument> indexed = new LinkedHashMap<>();
		Map<String, String> originalContent = originalContent(documents);
		for (ResolvedDocument document : documents) {
			try {
				indexed.put(document.sourceType(), new IndexedDocument(document, indexingService.ensureIndexed(document)));
			}
			catch (RuntimeException exception) {
				indexed.put(document.sourceType(), new IndexedDocument(document, Optional.empty()));
				log.warn("rag_index_fallback sourceType={}", document.sourceType());
			}
		}

		Map<String, Candidate> candidates = new LinkedHashMap<>();
		Set<DocumentSourceType> fallbackSources = new LinkedHashSet<>();
		for (String query : queries) {
			String retrievalQuery = safe(query).trim();
			if (retrievalQuery.isEmpty()) {
				continue;
			}
			for (IndexedDocument source : indexed.values()) {
				if (source.index().isEmpty()) {
					fallbackSources.add(source.document().sourceType());
					continue;
				}
				try {
					List<RagContextSnippet> retrieved = retrievalService.retrieve(
						retrievalQuery,
						source.index().orElseThrow(),
						candidateTopK(source.document().sourceType())
					);
					if (retrieved.isEmpty()) {
						fallbackSources.add(source.document().sourceType());
					}
					for (int rank = 0; rank < retrieved.size(); rank++) {
						RagContextSnippet snippet = restoreOriginalContent(retrieved.get(rank), originalContent);
						addVectorCandidate(candidates, snippet, source.document().sourceType(), rank + 1);
					}
				}
				catch (RuntimeException exception) {
					fallbackSources.add(source.document().sourceType());
					log.warn("rag_retrieval_fallback sourceType={}", source.document().sourceType());
				}
			}
		}

		for (DocumentSourceType sourceType : fallbackSources) {
			IndexedDocument source = indexed.get(sourceType);
			if (source != null) {
				addLocalCandidates(candidates, source.document());
			}
		}
		if (candidates.isEmpty()) {
			for (ResolvedDocument document : documents) {
				addLocalCandidates(candidates, document);
			}
		}

		List<RagContextSnippet> snippets = select(candidates.values(), profile);
		boolean vectorBacked = snippets.stream().anyMatch(snippet -> candidates.get(snippet.sourceContextId()).vectorBacked());
		meterRegistry.counter("ai.rag.context", "mode", vectorBacked ? "retrieval" : "local", "workflow", profile.name())
			.increment();
		log.info("rag_context_ready mode={} workflow={} candidates={} snippets={}",
			vectorBacked ? "retrieval" : "local", profile.name(), candidates.size(), snippets.size());
		return context("document-indexes", snippets, profile.budget(), vectorBacked, true);
	}

	private List<RagContextSnippet> select(Iterable<Candidate> candidates, SelectionProfile profile) {
		List<Candidate> ranked = new ArrayList<>();
		candidates.forEach(ranked::add);
		ranked.sort(candidateOrder());
		List<Candidate> selected = new ArrayList<>();
		selectFrom(ranked, selected, profile.jobDescriptionMinimum(), DocumentSourceType.JOB_DESCRIPTION, true, profile);
		selectFrom(ranked, selected, profile.budget(), null, true, profile);
		selectFrom(ranked, selected, profile.budget(), null, false, profile);
		selected.sort(candidateOrder());
		for (Candidate candidate : selected) {
			meterRegistry.counter(
				"ai.rag.context.selection",
				"workflow", profile.name(),
				"source", candidate.sourceType().metadataValue(),
				"mode", candidate.vectorBacked() ? "retrieval" : "local"
			).increment();
		}
		return selected.stream().map(Candidate::snippet).toList();
	}

	private void selectFrom(
		List<Candidate> ranked,
		List<Candidate> selected,
		int limit,
		DocumentSourceType requiredSource,
		boolean enforceSectionLimit,
		SelectionProfile profile
	) {
		int selectedForSource = 0;
		for (Candidate candidate : ranked) {
			if (selected.size() >= profile.budget()) {
				return;
			}
			if (requiredSource != null && candidate.sourceType() != requiredSource) {
				continue;
			}
			if (requiredSource != null && selectedForSource >= limit) {
				return;
			}
			if (selected.contains(candidate) || (enforceSectionLimit && sectionLimitReached(selected, candidate))
				|| nearDuplicate(selected, candidate)) {
				continue;
			}
			selected.add(candidate);
			if (requiredSource != null) {
				selectedForSource++;
			}
		}
	}

	private boolean sectionLimitReached(List<Candidate> selected, Candidate candidate) {
		long count = selected.stream().filter(existing -> existing.sourceType() == candidate.sourceType()
			&& existing.section().equals(candidate.section())).count();
		return count >= properties.sectionMaximum();
	}

	private boolean nearDuplicate(List<Candidate> selected, Candidate candidate) {
		return selected.stream().anyMatch(existing -> existing.sourceType() == candidate.sourceType()
			&& existing.section().equals(candidate.section())
			&& Math.abs(existing.chunkIndex() - candidate.chunkIndex()) <= 1
			&& overlappingBoundary(existing.snippet().content(), candidate.snippet().content()));
	}

	private boolean overlappingBoundary(String first, String second) {
		String left = normalizedBoundary(first, false);
		String right = normalizedBoundary(second, true);
		return left.length() >= 80 && right.length() >= 80 && (left.endsWith(right) || right.startsWith(left));
	}

	private String normalizedBoundary(String value, boolean prefix) {
		String normalized = safe(value).replaceAll("\\s+", " ").trim();
		int length = Math.min(180, normalized.length());
		return prefix ? normalized.substring(0, length) : normalized.substring(normalized.length() - length);
	}

	private Comparator<Candidate> candidateOrder() {
		return Comparator.comparingDouble(Candidate::rrfScore).reversed()
			.thenComparingInt(Candidate::bestRank)
			.thenComparing(candidate -> candidate.snippet().sourceContextId());
	}

	private void addVectorCandidate(
		Map<String, Candidate> candidates,
		RagContextSnippet snippet,
		DocumentSourceType sourceType,
		int rank
	) {
		String contextId = snippet.sourceContextId();
		Candidate candidate = candidates.computeIfAbsent(
			contextId,
			ignored -> Candidate.vector(snippet, sourceType)
		);
		candidate.addRank(rank, properties.rrfK());
	}

	private void addLocalCandidates(Map<String, Candidate> candidates, ResolvedDocument document) {
		for (RagContextSnippet snippet : localSnippets(document)) {
			candidates.putIfAbsent(snippet.sourceContextId(), Candidate.local(snippet, document.sourceType()));
		}
	}

	private int candidateTopK(DocumentSourceType sourceType) {
		return sourceType == DocumentSourceType.JOB_DESCRIPTION
			? properties.jobDescriptionCandidateTopK()
			: properties.resumeCandidateTopK();
	}

	private Map<String, String> originalContent(List<ResolvedDocument> documents) {
		Map<String, String> content = new HashMap<>();
		for (ResolvedDocument document : documents) {
			for (RagContextSnippet snippet : localSnippets(document)) {
				content.put(snippet.sourceContextId(), snippet.content());
			}
		}
		return content;
	}

	private RagContextSnippet restoreOriginalContent(RagContextSnippet snippet, Map<String, String> originalContent) {
		String contextId = snippet.sourceContextId();
		String content = originalContent.getOrDefault(contextId, snippet.content());
		return new RagContextSnippet(snippet.id(), content, snippet.metadata(), snippet.score());
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
			snippets.stream().map(RagContextSnippet::sourceContextId).filter(value -> !blank(value)).distinct().toList(),
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
			? chunker.chunk(document.normalizedText()).stream().map(chunk -> new DocumentChunk(
				chunk.index(), chunk.section(), chunk.content(),
				RagContextId.forChunk(document.sourceType().metadataValue(), chunk)
			)).toList()
			: document.persistedChunks();
		return chunks.stream().map(chunk -> {
			String contextId = blank(chunk.contextId())
				? RagContextId.forChunk(document.sourceType().metadataValue(), chunk.section(), chunk.index())
				: chunk.contextId();
			return new RagContextSnippet(
				"local-" + document.sourceType().metadataValue() + "-" + chunk.index(),
				chunk.content(),
				Map.of("contextId", contextId, "sourceType", document.sourceType().metadataValue(),
					"section", fallback(chunk.section(), "section"), "chunkIndex", chunk.index()),
				null
			);
		}).toList();
	}

	private String jobDescriptionQueryExcerpt(Optional<ResolvedDocument> jobDescription) {
		return jobDescription.map(ResolvedDocument::normalizedText)
			.map(text -> truncate(text, 1_000).replace('\n', ' ')).orElse("");
	}

	private String formatSnippets(List<RagContextSnippet> snippets, int maxSnippets, boolean truncateContent) {
		if (snippets.isEmpty()) {
			return "No retrieved context was available.";
		}
		return snippets.stream().limit(maxSnippets).map(snippet -> formatSnippet(snippet, truncateContent))
			.reduce((left, right) -> left + "\n\n" + right).orElse("No retrieved context was available.");
	}

	private String formatSnippet(RagContextSnippet snippet, boolean truncateContent) {
		Map<String, Object> metadata = snippet.metadata() == null ? Map.of() : snippet.metadata();
		return """
			[contextId=%s source=%s section=%s score=%s]
			%s
			""".formatted(
			snippet.sourceContextId(), metadataValue(metadata, "sourceType"), metadataValue(metadata, "section"),
			snippet.score() == null ? "n/a" : "%.4f".formatted(snippet.score()),
			truncateContent ? truncate(snippet.content(), 1_200) : safe(snippet.content())
		).trim();
	}

	private String metadataValue(Map<String, Object> metadata, String key) {
		Object value = metadata.get(key);
		return value == null ? "unknown" : value.toString();
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

	private record IndexedDocument(ResolvedDocument document, Optional<RagDocumentIndexHandle> index) {
	}

	private record SelectionProfile(String name, int budget, int jobDescriptionMinimum) {
	}

	private static final class Candidate {

		private final RagContextSnippet snippet;
		private final DocumentSourceType sourceType;
		private final boolean vectorBacked;
		private double rrfScore;
		private int bestRank;

		private Candidate(RagContextSnippet snippet, DocumentSourceType sourceType, boolean vectorBacked) {
			this.snippet = snippet;
			this.sourceType = sourceType;
			this.vectorBacked = vectorBacked;
			this.bestRank = Integer.MAX_VALUE;
		}

		static Candidate vector(RagContextSnippet snippet, DocumentSourceType sourceType) {
			return new Candidate(snippet, sourceType, true);
		}

		static Candidate local(RagContextSnippet snippet, DocumentSourceType sourceType) {
			return new Candidate(snippet, sourceType, false);
		}

		void addRank(int rank, int rrfK) {
			rrfScore += 1.0d / (rrfK + rank);
			bestRank = Math.min(bestRank, rank);
		}

		RagContextSnippet snippet() { return snippet; }
		DocumentSourceType sourceType() { return sourceType; }
		boolean vectorBacked() { return vectorBacked; }
		double rrfScore() { return rrfScore; }
		int bestRank() { return bestRank; }
		String section() { return String.valueOf(snippet.metadata().getOrDefault("section", "section")); }
		int chunkIndex() {
			Object value = snippet.metadata().get("chunkIndex");
			return value instanceof Number number ? number.intValue() : Integer.MAX_VALUE;
		}
	}
}
