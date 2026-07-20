package dev.jiaming.ai_interview.storage;

import java.util.Map;

public interface ObjectStorageService {

	default StoredObject put(String key, byte[] content, String contentType, Map<String, String> metadata) {
		return put(key, content, contentType, metadata, Map.of());
	}

	StoredObject put(
		String key,
		byte[] content,
		String contentType,
		Map<String, String> metadata,
		Map<String, String> tags
	);

	StoredObjectContent get(String key);

	void delete(String key);

	void tag(String key, Map<String, String> tags);
}
