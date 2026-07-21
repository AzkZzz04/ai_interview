package dev.jiaming.ai_interview.rag;

import java.util.UUID;

public record RagDocumentIndexHandle(UUID indexId, long claimVersion) {
}
