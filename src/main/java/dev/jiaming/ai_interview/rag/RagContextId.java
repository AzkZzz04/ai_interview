package dev.jiaming.ai_interview.rag;

import java.util.Locale;

import dev.jiaming.ai_interview.resume.TextChunk;

public final class RagContextId {

	private RagContextId() {
	}

	public static String forChunk(String sourceType, TextChunk chunk) {
		return forChunk(sourceType, chunk.section(), chunk.index());
	}

	public static String forChunk(String sourceType, String section, int chunkIndex) {
		return normalizeSourceType(sourceType) + ":" + slug(section, "section") + ":" + chunkIndex;
	}

	private static String normalizeSourceType(String sourceType) {
		return slug(sourceType, "source").replace('-', '_');
	}

	private static String slug(String value, String fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}
		String slug = value.trim()
			.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("(^-|-$)", "");
		return slug.isBlank() ? fallback : slug;
	}
}
