package dev.jiaming.ai_interview.rag;

import java.time.Instant;
import java.util.UUID;

record RagDocumentIndex(
	UUID indexId,
	RagDocumentIndexIdentity identity,
	RagDocumentIndexStatus status,
	long claimVersion,
	Instant indexingStartedAt,
	int documentCount,
	Instant updatedAt,
	Instant lastUsedAt
) {
}
