package dev.jiaming.ai_interview.resume;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class SectionAwareTextChunker {

	private static final int DEFAULT_MAX_CHARS = 1_800;

	private static final int DEFAULT_OVERLAP_CHARS = 180;

	private static final String DEFAULT_SECTION = "Summary";

	private static final Set<String> KNOWN_SECTION_HEADINGS = Set.of(
		"summary",
		"professional summary",
		"profile",
		"objective",
		"experience",
		"work experience",
		"professional experience",
		"employment",
		"work history",
		"research experience",
		"projects",
		"project experience",
		"selected projects",
		"personal projects",
		"academic projects",
		"technical projects",
		"skills",
		"technical skills",
		"core competencies",
		"technologies",
		"tools and technologies",
		"education",
		"academic background",
		"coursework",
		"relevant coursework",
		"certifications",
		"certificates",
		"publications",
		"awards",
		"leadership",
		"volunteering"
	);

	public List<TextChunk> chunk(String normalizedText) {
		return chunk(normalizedText, DEFAULT_MAX_CHARS, DEFAULT_OVERLAP_CHARS);
	}

	public List<TextChunk> chunk(String normalizedText, int maxChars, int overlapChars) {
		if (normalizedText == null || normalizedText.isBlank()) {
			return List.of();
		}
		if (maxChars < 400) {
			throw new IllegalArgumentException("maxChars must be at least 400");
		}
		if (overlapChars < 0 || overlapChars >= maxChars) {
			throw new IllegalArgumentException("overlapChars must be non-negative and smaller than maxChars");
		}

		List<SectionBlock> sections = collectSections(normalizedText);
		List<TextChunk> chunks = new ArrayList<>();
		for (SectionBlock section : sections) {
			chunks.addAll(splitSection(section, chunks.size(), maxChars, overlapChars));
		}
		return chunks;
	}

	private List<SectionBlock> collectSections(String normalizedText) {
		List<SectionBlock> sections = new ArrayList<>();
		String currentSection = DEFAULT_SECTION;
		StringBuilder content = new StringBuilder();

		for (String line : normalizedText.split("\n")) {
			if (isSectionHeading(line)) {
				appendSection(sections, currentSection, content);
				currentSection = canonicalSectionName(line);
				content = new StringBuilder();
			}
			else {
				if (!content.isEmpty()) {
					content.append('\n');
				}
				content.append(line);
			}
		}
		appendSection(sections, currentSection, content);
		return sections;
	}

	private void appendSection(List<SectionBlock> sections, String section, StringBuilder content) {
		String text = content.toString().trim();
		if (!text.isBlank()) {
			sections.add(new SectionBlock(section, text));
		}
	}

	private List<TextChunk> splitSection(SectionBlock section, int startIndex, int maxChars, int overlapChars) {
		List<TextChunk> chunks = new ArrayList<>();
		String content = section.content();
		int cursor = 0;

		while (cursor < content.length()) {
			int end = Math.min(cursor + maxChars, content.length());
			if (end < content.length()) {
				end = findNaturalBreak(content, cursor, end);
			}

			String chunkContent = content.substring(cursor, end).trim();
			if (!chunkContent.isBlank()) {
				chunks.add(new TextChunk(startIndex + chunks.size(), section.name(), chunkContent));
			}

			if (end >= content.length()) {
				break;
			}
			cursor = Math.max(0, end - overlapChars);
		}
		return chunks;
	}

	private int findNaturalBreak(String content, int cursor, int proposedEnd) {
		int paragraphBreak = content.lastIndexOf("\n\n", proposedEnd);
		if (paragraphBreak > cursor + 200) {
			return paragraphBreak;
		}

		int lineBreak = content.lastIndexOf('\n', proposedEnd);
		if (lineBreak > cursor + 200) {
			return lineBreak;
		}

		int sentenceBreak = content.lastIndexOf(". ", proposedEnd);
		if (sentenceBreak > cursor + 200) {
			return sentenceBreak + 1;
		}

		return proposedEnd;
	}

	private boolean isSectionHeading(String line) {
		String cleaned = line.trim();
		if (cleaned.isBlank() || cleaned.length() > 64) {
			return false;
		}

		String normalized = cleaned
			.replace(":", "")
			.toLowerCase(Locale.ROOT)
			.trim();

		if (KNOWN_SECTION_HEADINGS.contains(normalized)) {
			return true;
		}

		boolean allCaps = cleaned.equals(cleaned.toUpperCase(Locale.ROOT));
		return allCaps && KNOWN_SECTION_HEADINGS.contains(normalized);
	}

	private String canonicalSectionName(String heading) {
		String cleaned = heading.replace(":", "").trim().toLowerCase(Locale.ROOT);
		return switch (cleaned) {
			case "work experience", "professional experience", "employment", "work history" -> "Experience";
			case "research experience" -> "Research Experience";
			case "technical skills", "technologies" -> "Skills";
			case "core competencies", "tools and technologies" -> "Skills";
			case "project experience", "selected projects", "personal projects", "academic projects",
				"technical projects" -> "Projects";
			case "academic background" -> "Education";
			case "coursework", "relevant coursework" -> "Coursework";
			case "certificates" -> "Certifications";
			case "professional summary", "profile", "objective" -> "Summary";
			default -> Character.toUpperCase(cleaned.charAt(0)) + cleaned.substring(1);
		};
	}

	private record SectionBlock(String name, String content) {
	}
}
