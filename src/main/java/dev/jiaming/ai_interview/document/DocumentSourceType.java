package dev.jiaming.ai_interview.document;

public enum DocumentSourceType {
	RESUME("resume"),
	JOB_DESCRIPTION("job_description");

	private final String metadataValue;

	DocumentSourceType(String metadataValue) {
		this.metadataValue = metadataValue;
	}

	public String metadataValue() {
		return metadataValue;
	}
}
