package dev.jiaming.ai_interview.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(
	int embeddingDimensions,
	int defaultTopK,
	String embeddingModel,
	String chunkSchema
) {
	public RagProperties {
		embeddingDimensions = embeddingDimensions <= 0 ? 1_024 : embeddingDimensions;
		defaultTopK = defaultTopK <= 0 ? 8 : defaultTopK;
		embeddingModel = blank(embeddingModel) ? "gemini-embedding-001" : embeddingModel.trim();
		chunkSchema = blank(chunkSchema) ? "section-context-v2" : chunkSchema.trim();
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}
}
