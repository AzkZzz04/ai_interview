package dev.jiaming.ai_interview.rag;

import dev.jiaming.ai_interview.document.DocumentSourceType;

record RagDocumentIndexIdentity(
	DocumentSourceType sourceType,
	String contentHash,
	String embeddingModel,
	int embeddingDimensions,
	String chunkSchema
) {
}
