package dev.jiaming.ai_interview.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

@Component
public class ContentHasher {

	public String sha256(String normalizedText) {
		if (normalizedText == null) {
			throw new IllegalArgumentException("Normalized text is required");
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(normalizedText.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}
