package dev.jiaming.ai_interview.document;

import java.util.List;
import java.util.UUID;

public record ResolvedDocument(
	DocumentSourceType sourceType,
	UUID resourceId,
	String contentHash,
	String normalizedText,
	List<DocumentChunk> persistedChunks
) {
	public ResolvedDocument {
		persistedChunks = persistedChunks == null ? List.of() : List.copyOf(persistedChunks);
	}
}
