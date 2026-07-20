export type EvidenceChunk = {
  index: number;
  section: string;
  content?: string;
};

export type EvidenceSources = {
  resumeText?: string;
  jobDescription?: string;
  resumeChunks?: EvidenceChunk[];
};

export type EvidenceSectionSegment = {
  section: string;
  content: string;
};

const MAX_CHARS = 1_800;
const OVERLAP_CHARS = 180;
const DEFAULT_SECTION = "Summary";

const sectionNames = new Map<string, string>([
  ["summary", "Summary"],
  ["professional summary", "Summary"],
  ["profile", "Summary"],
  ["objective", "Summary"],
  ["experience", "Experience"],
  ["work experience", "Experience"],
  ["professional experience", "Experience"],
  ["employment", "Experience"],
  ["work history", "Experience"],
  ["research experience", "Research Experience"],
  ["projects", "Projects"],
  ["project experience", "Projects"],
  ["selected projects", "Projects"],
  ["personal projects", "Projects"],
  ["academic projects", "Projects"],
  ["technical projects", "Projects"],
  ["skills", "Skills"],
  ["technical skills", "Skills"],
  ["core competencies", "Skills"],
  ["technologies", "Skills"],
  ["tools and technologies", "Skills"],
  ["education", "Education"],
  ["academic background", "Education"],
  ["coursework", "Coursework"],
  ["relevant coursework", "Coursework"],
  ["certifications", "Certifications"],
  ["certificates", "Certifications"],
  ["publications", "Publications"],
  ["awards", "Awards"],
  ["leadership", "Leadership"],
  ["volunteering", "Volunteering"]
]);

export function buildEvidenceSectionLookup({
  resumeText = "",
  jobDescription = "",
  resumeChunks = []
}: EvidenceSources) {
  const resume = new Map(
    chunkSections(resumeText).map((chunk, index) => [index, [chunk]])
  );
  resumeChunks.forEach((chunk) => {
    const segments = splitEvidenceSections(chunk.section, chunk.content);
    if (segments.length) {
      resume.set(chunk.index, segments);
    }
  });

  return {
    resume,
    jobDescription: new Map(
      chunkSections(jobDescription).map((chunk, index) => [index, [chunk]])
    )
  };
}

export function relevantSectionNames(
  segments: EvidenceSectionSegment[] | undefined,
  evidenceHint = ""
) {
  if (!segments?.length) {
    return [];
  }

  const distinctSections = [...new Set(segments.map((segment) => segment.section))];
  const hintTokens = meaningfulTokens(evidenceHint);
  if (hintTokens.size === 0 || distinctSections.length === 1) {
    return distinctSections;
  }

  const scoredSegments = segments.map((segment) => ({
    section: segment.section,
    score: overlapCount(hintTokens, meaningfulTokens(segment.content))
  }));
  const bestScore = Math.max(...scoredSegments.map(({ score }) => score));
  if (bestScore === 0) {
    return distinctSections;
  }

  return [...new Set(
    scoredSegments
      .filter(({ score }) => score === bestScore)
      .map(({ section }) => section)
  )];
}

export function titleCaseEvidenceSection(value: string) {
  return value
    .split(/[-_\s]+/)
    .filter(Boolean)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");
}

function chunkSections(text: string): EvidenceSectionSegment[] {
  if (!text.trim()) {
    return [];
  }

  const sections: Array<{ name: string; content: string }> = [];
  let currentSection = DEFAULT_SECTION;
  let content: string[] = [];

  const appendSection = () => {
    const sectionContent = content.join("\n").trim();
    if (sectionContent) {
      sections.push({ name: currentSection, content: sectionContent });
    }
  };

  text.split("\n").forEach((line) => {
    const section = canonicalSectionName(line);
    if (section) {
      appendSection();
      currentSection = section;
      content = [];
    }
    else {
      content.push(line);
    }
  });
  appendSection();

  return sections.flatMap((section) => splitSection(section.name, section.content));
}

function splitSection(section: string, content: string) {
  const chunks: EvidenceSectionSegment[] = [];
  let cursor = 0;

  while (cursor < content.length) {
    let end = Math.min(cursor + MAX_CHARS, content.length);
    if (end < content.length) {
      end = findNaturalBreak(content, cursor, end);
    }
    const chunkContent = content.slice(cursor, end).trim();
    if (chunkContent) {
      chunks.push({ section, content: chunkContent });
    }
    if (end >= content.length) {
      break;
    }
    cursor = Math.max(0, end - OVERLAP_CHARS);
  }

  return chunks;
}

function findNaturalBreak(content: string, cursor: number, proposedEnd: number) {
  const paragraphBreak = content.lastIndexOf("\n\n", proposedEnd);
  if (paragraphBreak > cursor + 200) {
    return paragraphBreak;
  }
  const lineBreak = content.lastIndexOf("\n", proposedEnd);
  if (lineBreak > cursor + 200) {
    return lineBreak;
  }
  const sentenceBreak = content.lastIndexOf(". ", proposedEnd);
  if (sentenceBreak > cursor + 200) {
    return sentenceBreak + 1;
  }
  return proposedEnd;
}

function canonicalSectionName(line: string) {
  const cleaned = line.trim();
  if (!cleaned || cleaned.length > 64) {
    return null;
  }
  return sectionNames.get(cleaned.replace(/:$/, "").trim().toLowerCase()) ?? null;
}

function splitEvidenceSections(baseSection: string, content = "") {
  const segments: EvidenceSectionSegment[] = [];
  let currentSection = baseSection.trim() || DEFAULT_SECTION;
  let lines: string[] = [];

  const appendSegment = () => {
    const segmentContent = lines.join("\n").trim();
    if (segmentContent) {
      segments.push({ section: currentSection, content: segmentContent });
    }
  };

  content.split("\n").forEach((line) => {
    const embeddedSection = canonicalSectionName(line);
    if (embeddedSection) {
      appendSegment();
      currentSection = embeddedSection;
      lines = [];
    }
    else {
      lines.push(line);
    }
  });
  appendSegment();

  return segments;
}

function meaningfulTokens(value: string) {
  const ignoredWords = new Set([
    "about", "after", "approach", "could", "describe", "from", "have", "how",
    "into", "that", "the", "their", "this", "under", "what", "when", "where",
    "which", "with", "would", "your", "you"
  ]);
  return new Set(
    value
      .toLowerCase()
      .split(/[^a-z0-9+#.]+/)
      .filter((word) => word.length > 2 && !ignoredWords.has(word))
  );
}

function overlapCount(left: Set<string>, right: Set<string>) {
  let overlap = 0;
  left.forEach((token) => {
    if (right.has(token)) {
      overlap += 1;
    }
  });
  return overlap;
}
