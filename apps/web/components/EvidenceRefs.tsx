"use client";

import { useMemo } from "react";
import {
  buildEvidenceSectionLookup,
  relevantSectionNames,
  titleCaseEvidenceSection
} from "@/lib/evidence";
import type { EvidenceChunk } from "@/lib/evidence";

function evidenceLabels(
  id: string,
  sections: ReturnType<typeof buildEvidenceSectionLookup>,
  evidenceHint: string
) {
  const [source = "", section = ""] = id.split(":");
  const normalizedSource = source.toLowerCase();
  const hasNamedSection = Boolean(section) && !/^\d+$/.test(section);
  const chunkIndex = /^\d+$/.test(section) ? Number(section) : null;

  if (normalizedSource === "resume") {
    const sectionNames = hasNamedSection
      ? [section]
      : relevantSectionNames(sections.resume.get(chunkIndex ?? -1), evidenceHint);
    return sectionNames.length
      ? sectionNames.map(titleCaseEvidenceSection)
      : ["Resume"];
  }
  if (normalizedSource === "job_description" || normalizedSource === "job-description") {
    const sectionNames = hasNamedSection
      ? [section]
      : relevantSectionNames(sections.jobDescription.get(chunkIndex ?? -1), evidenceHint);
    return sectionNames.length
      ? sectionNames.map((name) => `Job description: ${titleCaseEvidenceSection(name)}`)
      : ["Job description"];
  }
  return [hasNamedSection
    ? `${titleCaseEvidenceSection(source)}: ${titleCaseEvidenceSection(section)}`
    : titleCaseEvidenceSection(source || id)];
}

export function EvidenceRefs({
  ids,
  resumeText,
  jobDescription,
  resumeChunks,
  evidenceHint = ""
}: {
  ids?: string[];
  resumeText?: string;
  jobDescription?: string;
  resumeChunks?: EvidenceChunk[];
  evidenceHint?: string;
}) {
  const sections = useMemo(
    () => buildEvidenceSectionLookup({ resumeText, jobDescription, resumeChunks }),
    [jobDescription, resumeChunks, resumeText]
  );
  const references = [
    ...new Map(
      (ids?.filter(Boolean) ?? []).flatMap((id) =>
        evidenceLabels(id, sections, evidenceHint).map((label) => [label, id] as const)
      )
    ).entries()
  ];
  if (!references.length) {
    return null;
  }

  return (
    <div className="evidence-refs" aria-label="Retrieved evidence">
      <strong>Evidence</strong>
      <div>
        {references.map(([label, id]) => (
          <span key={label} title={id}>
            {label}
          </span>
        ))}
      </div>
    </div>
  );
}
