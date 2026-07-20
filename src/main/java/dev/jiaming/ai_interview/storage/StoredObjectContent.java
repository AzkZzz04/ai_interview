package dev.jiaming.ai_interview.storage;

import java.util.Map;

public record StoredObjectContent(
	byte[] bytes,
	String contentType,
	Map<String, String> metadata
) {
}
