package dev.jiaming.ai_interview.common;

import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.runtime")
public record RuntimeModeProperties(String mode) {

	public RuntimeModeProperties {
		mode = mode == null || mode.isBlank() ? "all" : mode.trim().toLowerCase(Locale.ROOT);
		if (!"all".equals(mode) && !"api".equals(mode) && !"worker".equals(mode)) {
			throw new IllegalArgumentException("JOB_RUNTIME_MODE must be all, api, or worker");
		}
	}

	public boolean apiEnabled() {
		return !"worker".equals(mode);
	}

	public boolean workerEnabled() {
		return !"api".equals(mode);
	}
}
