package dev.jiaming.ai_interview.coach;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import dev.jiaming.ai_interview.rag.RagContextId;
import dev.jiaming.ai_interview.rag.RagContextSnippet;
import dev.jiaming.ai_interview.rag.RagCorpus;
import dev.jiaming.ai_interview.rag.RagIndexingService;
import dev.jiaming.ai_interview.rag.RagRetrievalService;
import dev.jiaming.ai_interview.resume.SectionAwareTextChunker;
import dev.jiaming.ai_interview.resume.TextChunk;

@Service
public class CoachRagContextService {

	private static final Logger log = LoggerFactory.getLogger(CoachRagContextService.class);

	private static final int RAG_TOP_K_PER_QUERY = 4;

	private final SectionAwareTextChunker chunker;

	private final ObjectProvider<RagIndexingService> ragIndexingServiceProvider;

	private final ObjectProvider<RagRetrievalService> ragRetrievalServiceProvider;

	public CoachRagContextService(
		SectionAwareTextChunker chunker,
		ObjectProvider<RagIndexingService> ragIndexingServiceProvider,
		ObjectProvider<RagRetrievalService> ragRetrievalServiceProvider
	) {
		this.chunker = chunker;
		this.ragIndexingServiceProvider = ragIndexingServiceProvider;
		this.ragRetrievalServiceProvider = ragRetrievalServiceProvider;
	}

	public CoachRagContext assessmentContext(AiAnalysisRequest request, String resumeText) {
		return ragContext(
			request,
			resumeText,
			assessmentQueries(request),
			List.of("resume", "job_description"),
			14
		);
	}

	public CoachRagContext questionContext(AiAnalysisRequest request, String resumeText) {
		return ragContext(
			request,
			resumeText,
			questionQueries(request),
			List.of("resume", "job_description"),
			16
		);
	}

	public CoachRagContext feedbackContext(AnswerFeedbackRequest request, String resumeText) {
		return ragContext(
			new AiAnalysisRequest(
				request.resumeText(),
				request.jobDescription(),
				request.targetRole(),
				request.seniority()
			),
			resumeText,
			feedbackQueries(request),
			List.of("resume", "job_description"),
			10
		);
	}

	private CoachRagContext ragContext(
		AiAnalysisRequest request,
		String resumeText,
		List<String> queries,
		List<String> sourceTypes,
		int maxSnippets
	) {
		RagIndexingService indexingService = ragIndexingServiceProvider.getIfAvailable();
		RagRetrievalService retrievalService = ragRetrievalServiceProvider.getIfAvailable();
		if (indexingService != null && retrievalService != null) {
			try {
				RagCorpus corpus = indexingService.index(
					resumeText,
					fallback(request.jobDescription(), ""),
					fallback(request.targetRole(), "Software Engineer"),
					fallback(request.seniority(), "Mid-level")
				);
				List<RagContextSnippet> snippets = retrieveSnippets(retrievalService, corpus, queries, sourceTypes, maxSnippets);
				if (!snippets.isEmpty()) {
					log.info(
						"rag_context_ready corpusId={} vectorBacked=true snippets={} sourceContextIds={}",
						corpus.id(),
						snippets.size(),
						sourceContextIds(snippets)
					);
					return new CoachRagContext(
						corpus.id(),
						formatSnippets(snippets, maxSnippets),
						sourceContextIds(snippets),
						true
					);
				}
			}
			catch (RuntimeException exception) {
				log.warn("rag_context_fallback reason={}", exception.getMessage());
			}
		}

		List<RagContextSnippet> fallbackSnippets = fallbackSnippets(resumeText, request.jobDescription(), maxSnippets);
		log.info("rag_context_ready corpusId=local-chunks vectorBacked=false snippets={}", fallbackSnippets.size());
		return new CoachRagContext(
			"local-chunks",
			formatSnippets(fallbackSnippets, maxSnippets),
			sourceContextIds(fallbackSnippets),
			false
		);
	}

	private List<RagContextSnippet> retrieveSnippets(
		RagRetrievalService retrievalService,
		RagCorpus corpus,
		List<String> queries,
		List<String> sourceTypes,
		int maxSnippets
	) {
		Map<String, RagContextSnippet> snippetsByContextId = new LinkedHashMap<>();
		for (String query : queries) {
			String retrievalQuery = fallback(query, "").trim();
			if (blank(retrievalQuery)) {
				continue;
			}
			List<RagContextSnippet> snippets = retrievalService.retrieve(
				retrievalQuery,
				corpus.id(),
				sourceTypes,
				RAG_TOP_K_PER_QUERY
			);
			for (RagContextSnippet snippet : snippets) {
				snippetsByContextId.putIfAbsent(snippet.sourceContextId(), snippet);
				if (snippetsByContextId.size() >= maxSnippets) {
					return new ArrayList<>(snippetsByContextId.values());
				}
			}
		}
		return new ArrayList<>(snippetsByContextId.values());
	}

	private List<String> assessmentQueries(AiAnalysisRequest request) {
		String role = fallback(request.targetRole(), "Software Engineer");
		String seniority = fallback(request.seniority(), "Mid-level");
		String jobDescription = fallback(request.jobDescription(), "");
		return List.of(
			"technical depth systems ownership architecture complexity " + role + " " + seniority,
			"measurable impact metrics scale latency reliability cost adoption outcomes",
			"role alignment required skills must have requirements " + role + " " + jobDescription,
			"resume gaps missing evidence weak bullets seniority signal " + role + " " + seniority
		);
	}

	private List<String> questionQueries(AiAnalysisRequest request) {
		String role = fallback(request.targetRole(), "Software Engineer");
		String seniority = fallback(request.seniority(), "Mid-level");
		String jobDescription = fallback(request.jobDescription(), "");
		return List.of(
			"strongest projects ownership technical complexity " + role + " " + seniority,
			"weakest resume areas missing detail interview probe " + role,
			"system design architecture scaling data flow production tradeoffs",
			"debugging incident response observability database cache production",
			"collaboration leadership stakeholder tradeoff communication",
			"job description requirements role specific tooling " + jobDescription
		);
	}

	private List<String> feedbackQueries(AnswerFeedbackRequest request) {
		return List.of(
			fallback(request.questionText(), ""),
			String.join(" ", safeList(request.expectedSignals())),
			fallback(request.category(), "") + " " + fallback(request.targetRole(), ""),
			"source project context expected evidence answer evaluation"
		);
	}

	private List<RagContextSnippet> fallbackSnippets(String resumeText, String jobDescription, int maxSnippets) {
		List<RagContextSnippet> snippets = new ArrayList<>();
		for (TextChunk chunk : chunker.chunk(resumeText)) {
			String contextId = RagContextId.forChunk("resume", chunk);
			snippets.add(new RagContextSnippet(
				"local-resume-" + chunk.index(),
				chunk.content(),
				Map.of(
					"contextId", contextId,
					"sourceType", "resume",
					"section", chunk.section(),
					"chunkIndex", chunk.index()
				),
				null
			));
			if (snippets.size() >= maxSnippets) {
				return snippets;
			}
		}

		for (TextChunk chunk : chunker.chunk(fallback(jobDescription, ""))) {
			String contextId = RagContextId.forChunk("job_description", chunk);
			snippets.add(new RagContextSnippet(
				"local-job-description-" + chunk.index(),
				chunk.content(),
				Map.of(
					"contextId", contextId,
					"sourceType", "job_description",
					"section", chunk.section(),
					"chunkIndex", chunk.index()
				),
				null
			));
			if (snippets.size() >= maxSnippets) {
				return snippets;
			}
		}

		return snippets;
	}

	private String formatSnippets(List<RagContextSnippet> snippets, int maxSnippets) {
		if (snippets.isEmpty()) {
			return "No retrieved context was available.";
		}
		return snippets.stream()
			.limit(maxSnippets)
			.map(this::formatSnippet)
			.reduce((left, right) -> left + "\n\n" + right)
			.orElse("No retrieved context was available.");
	}

	private String formatSnippet(RagContextSnippet snippet) {
		Map<String, Object> metadata = snippet.metadata() == null ? Map.of() : snippet.metadata();
		return """
			[contextId=%s source=%s section=%s score=%s]
			%s
			""".formatted(
			snippet.sourceContextId(),
			metadataValue(metadata, "sourceType"),
			metadataValue(metadata, "section"),
			snippet.score() == null ? "n/a" : "%.4f".formatted(snippet.score()),
			truncate(snippet.content(), 1_200)
		).trim();
	}

	private List<String> sourceContextIds(List<RagContextSnippet> snippets) {
		return snippets.stream()
			.map(RagContextSnippet::sourceContextId)
			.filter(value -> !blank(value))
			.distinct()
			.limit(12)
			.toList();
	}

	private String truncate(String value, int limit) {
		String safeValue = fallback(value, "");
		if (safeValue.length() <= limit) {
			return safeValue;
		}
		return safeValue.substring(0, limit) + "\n[truncated]";
	}

	private String fallback(String value, String fallback) {
		return blank(value) ? fallback : value.trim();
	}

	private boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private <T> List<T> safeList(List<T> values) {
		return values == null ? List.of() : values;
	}

	private String metadataValue(Map<String, Object> metadata, String key) {
		Object value = metadata.get(key);
		return value == null ? "unknown" : value.toString();
	}
}
