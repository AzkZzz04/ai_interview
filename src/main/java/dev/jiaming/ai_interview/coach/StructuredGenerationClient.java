package dev.jiaming.ai_interview.coach;

/** Provider-neutral boundary for JSON-only model generation. */
public interface StructuredGenerationClient {

	String generateJson(String prompt);
}
