package dev.jiaming.ai_interview.resume;

import java.util.UUID;

public record ResumeExtractionJobPayload(
	UUID resumeId,
	String storageKey,
	String originalFilename,
	String contentType,
	String detectedContentType,
	long sizeBytes,
	String extension
) {
}
