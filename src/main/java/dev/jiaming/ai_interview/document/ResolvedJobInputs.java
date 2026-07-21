package dev.jiaming.ai_interview.document;

import java.util.Optional;

public record ResolvedJobInputs(
	ResolvedDocument resume,
	Optional<ResolvedDocument> jobDescription
) {
	public ResolvedJobInputs {
		jobDescription = jobDescription == null ? Optional.empty() : jobDescription;
	}
}
