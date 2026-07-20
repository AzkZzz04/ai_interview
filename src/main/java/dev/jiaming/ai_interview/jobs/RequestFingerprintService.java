package dev.jiaming.ai_interview.jobs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class RequestFingerprintService {

	private final ObjectMapper objectMapper;

	public RequestFingerprintService(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public String fingerprint(String action, Object source) {
		try {
			return sha256(objectMapper.writeValueAsString(List.of(action, source)));
		}
		catch (JsonProcessingException exception) {
			return sha256(action + ":" + String.valueOf(source));
		}
	}

	private String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}
