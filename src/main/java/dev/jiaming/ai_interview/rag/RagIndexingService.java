package dev.jiaming.ai_interview.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import dev.jiaming.ai_interview.resume.ResumeTextNormalizer;
import dev.jiaming.ai_interview.resume.SectionAwareTextChunker;
import dev.jiaming.ai_interview.resume.TextChunk;

@Service
public class RagIndexingService {

	private static final Logger log = LoggerFactory.getLogger(RagIndexingService.class);

	private static final String CONTEXT_ID_SCHEMA = "section-context";

	private final ObjectProvider<VectorStore> vectorStoreProvider;

	private final ResumeTextNormalizer normalizer;

	private final SectionAwareTextChunker chunker;

	private final Set<String> indexedCorpusIds = ConcurrentHashMap.newKeySet();

	public RagIndexingService(
		ObjectProvider<VectorStore> vectorStoreProvider,
		ResumeTextNormalizer normalizer,
		SectionAwareTextChunker chunker
	) {
		this.vectorStoreProvider = vectorStoreProvider;
		this.normalizer = normalizer;
		this.chunker = chunker;
	}

	public RagCorpus index(String resumeText, String jobDescription, String targetRole, String seniority) {
		VectorStore vectorStore = vectorStore();
		String normalizedResume = normalizer.normalize(resumeText);
		String normalizedJobDescription = normalizer.normalize(jobDescription);
		String corpusId = corpusId(normalizedResume, normalizedJobDescription, targetRole, seniority);

		List<Document> documents = new ArrayList<>();
		List<TextChunk> resumeChunks = chunker.chunk(normalizedResume);
		List<TextChunk> jobDescriptionChunks = chunker.chunk(normalizedJobDescription);
		addDocuments(documents, corpusId, "resume", resumeChunks, targetRole, seniority);
		addDocuments(documents, corpusId, "job_description", jobDescriptionChunks, targetRole, seniority);

		List<String> documentIds = documents.stream()
			.map(Document::getId)
			.toList();

		if (!documents.isEmpty() && indexedCorpusIds.add(corpusId)) {
			vectorStore.delete(documentIds);
			vectorStore.add(documents);
			log.info(
				"rag_corpus_indexed corpusId={} documents={} resumeChunks={} jobDescriptionChunks={}",
				corpusId,
				documents.size(),
				resumeChunks.size(),
				jobDescriptionChunks.size()
			);
		}

		return new RagCorpus(corpusId, documentIds, resumeChunks.size(), jobDescriptionChunks.size());
	}

	private VectorStore vectorStore() {
		VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
		if (vectorStore == null) {
			throw new IllegalStateException("VectorStore is not available");
		}
		return vectorStore;
	}

	private void addDocuments(
		List<Document> documents,
		String corpusId,
		String sourceType,
		List<TextChunk> chunks,
		String targetRole,
		String seniority
	) {
		for (TextChunk chunk : chunks) {
			String contextId = RagContextId.forChunk(sourceType, chunk);
			String documentId = documentId(corpusId, contextId);
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("corpusId", corpusId);
			metadata.put("contextId", contextId);
			metadata.put("sourceType", sourceType);
			metadata.put("section", chunk.section());
			metadata.put("chunkIndex", chunk.index());
			metadata.put("targetRole", fallback(targetRole));
			metadata.put("seniority", fallback(seniority));
			documents.add(new Document(documentId, chunk.content(), metadata));
		}
	}

	private String corpusId(String resumeText, String jobDescription, String targetRole, String seniority) {
		String input = String.join(
			"\n---\n",
			CONTEXT_ID_SCHEMA,
			fallback(resumeText),
			fallback(jobDescription),
			fallback(targetRole).toLowerCase(Locale.ROOT),
			fallback(seniority).toLowerCase(Locale.ROOT)
		);
		return "corpus-" + sha256(input).substring(0, 24);
	}

	private String documentId(String corpusId, String contextId) {
		return UUID.nameUUIDFromBytes((corpusId + ":" + contextId).getBytes(StandardCharsets.UTF_8)).toString();
	}

	private String sha256(String input) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}

	private String fallback(String value) {
		return value == null ? "" : value.trim();
	}
}
