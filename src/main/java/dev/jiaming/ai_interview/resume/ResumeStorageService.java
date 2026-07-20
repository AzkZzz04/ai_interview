package dev.jiaming.ai_interview.resume;

import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import dev.jiaming.ai_interview.storage.ObjectStorageService;
import dev.jiaming.ai_interview.storage.StoredObject;
import dev.jiaming.ai_interview.storage.StoredObjectContent;

@Service
public class ResumeStorageService {

	private final ObjectStorageService objectStorageService;

	public ResumeStorageService(ObjectProvider<ObjectStorageService> objectStorageServiceProvider) {
		this.objectStorageService = objectStorageServiceProvider.getIfAvailable();
	}

	public String store(ResumeFileContent fileContent) {
		if (objectStorageService == null) {
			throw new IllegalStateException("Object storage is required for asynchronous resume extraction");
		}
		StoredObject storedObject = objectStorageService.put(
			storageKey(fileContent.originalFilename()),
			fileContent.bytes(),
			fileContent.detectedContentType(),
			Map.of("original-filename", safeMetadata(fileContent.originalFilename())),
			Map.of("processing-status", "pending")
		);
		return storedObject.key();
	}

	public ResumeFileContent read(ResumeExtractionJobPayload payload) {
		if (objectStorageService == null) {
			throw new IllegalStateException("Object storage is required for asynchronous resume extraction");
		}
		StoredObjectContent storedObject = objectStorageService.get(payload.storageKey());
		return new ResumeFileContent(
			payload.originalFilename(),
			payload.contentType(),
			storedObject.bytes().length,
			storedObject.bytes(),
			payload.detectedContentType(),
			payload.extension()
		);
	}

	public void delete(String storageKey) {
		if (objectStorageService != null) {
			objectStorageService.delete(storageKey);
		}
	}

	public void markReady(String storageKey) {
		if (objectStorageService == null) {
			throw new IllegalStateException("Object storage is required for asynchronous resume extraction");
		}
		objectStorageService.tag(storageKey, Map.of("processing-status", "ready"));
	}

	private String storageKey(String filename) {
		return "resumes/%s/%s".formatted(UUID.randomUUID(), safeFilename(filename));
	}

	private String safeFilename(String filename) {
		String fallback = "resume";
		if (filename == null || filename.isBlank()) {
			return fallback;
		}
		String cleaned = filename.replaceAll("[^A-Za-z0-9._-]", "_");
		return cleaned.isBlank() ? fallback : cleaned;
	}

	private String safeMetadata(String value) {
		if (value == null || value.isBlank()) {
			return "unknown";
		}
		return value.replaceAll("[^\\x20-\\x7E]", "_");
	}
}
