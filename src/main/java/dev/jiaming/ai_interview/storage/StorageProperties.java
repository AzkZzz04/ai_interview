package dev.jiaming.ai_interview.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
	String endpoint,
	String region,
	String bucket,
	String accessKey,
	String secretKey,
	int pendingRetentionHours
) {

	public StorageProperties {
		pendingRetentionHours = pendingRetentionHours <= 0 ? 24 : pendingRetentionHours;
	}
}
