package dev.jiaming.ai_interview.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(
	int embeddingDimensions,
	int defaultTopK,
	int resumeCandidateTopK,
	int jobDescriptionCandidateTopK,
	int assessmentContextBudget,
	int questionContextBudget,
	int feedbackContextBudget,
	int assessmentJobDescriptionMinimum,
	int questionJobDescriptionMinimum,
	int feedbackJobDescriptionMinimum,
	int sectionMaximum,
	int rrfK,
	String embeddingModel,
	String chunkSchema
) {
	@ConstructorBinding
	public RagProperties {
		embeddingDimensions = embeddingDimensions <= 0 ? 1_024 : embeddingDimensions;
		defaultTopK = defaultTopK <= 0 ? 8 : defaultTopK;
		resumeCandidateTopK = resumeCandidateTopK <= 0 ? 6 : resumeCandidateTopK;
		jobDescriptionCandidateTopK = jobDescriptionCandidateTopK <= 0 ? 4 : jobDescriptionCandidateTopK;
		assessmentContextBudget = assessmentContextBudget <= 0 ? 14 : assessmentContextBudget;
		questionContextBudget = questionContextBudget <= 0 ? 16 : questionContextBudget;
		feedbackContextBudget = feedbackContextBudget <= 0 ? 10 : feedbackContextBudget;
		assessmentJobDescriptionMinimum = Math.max(0, assessmentJobDescriptionMinimum);
		questionJobDescriptionMinimum = Math.max(0, questionJobDescriptionMinimum);
		feedbackJobDescriptionMinimum = Math.max(0, feedbackJobDescriptionMinimum);
		sectionMaximum = sectionMaximum <= 0 ? 3 : sectionMaximum;
		rrfK = rrfK <= 0 ? 15 : rrfK;
		embeddingModel = blank(embeddingModel) ? "gemini-embedding-001" : embeddingModel.trim();
		chunkSchema = blank(chunkSchema) ? "section-block-v3" : chunkSchema.trim();
	}

	public RagProperties(int embeddingDimensions, int defaultTopK, String embeddingModel, String chunkSchema) {
		this(
			embeddingDimensions, defaultTopK, 6, 4, 14, 16, 10, 3, 3, 2, 3, 15,
			embeddingModel, chunkSchema
		);
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}
}
