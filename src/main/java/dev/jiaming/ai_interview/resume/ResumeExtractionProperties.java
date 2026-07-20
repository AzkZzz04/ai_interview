package dev.jiaming.ai_interview.resume;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.resume-extraction")
public record ResumeExtractionProperties(
	int queueCapacity,
	int timeoutSeconds,
	int maxParseChars,
	int maxPdfPages,
	int maxEmbeddedResources
) {

	public ResumeExtractionProperties {
		queueCapacity = queueCapacity <= 0 ? 2 : queueCapacity;
		timeoutSeconds = timeoutSeconds <= 0 ? 20 : timeoutSeconds;
		maxParseChars = maxParseChars <= 0 ? 250_000 : maxParseChars;
		maxPdfPages = maxPdfPages <= 0 ? 50 : maxPdfPages;
		maxEmbeddedResources = maxEmbeddedResources <= 0 ? 20 : maxEmbeddedResources;
	}
}
