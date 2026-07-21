package dev.jiaming.ai_interview.document;

public record DocumentChunk(
	int index,
	String section,
	String content,
	String contextId
) {
}
