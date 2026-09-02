export const SENIORITY_OPTIONS = [
  {
    value: "Intern",
    focus: "Fundamentals, projects, coursework, and learning potential."
  },
  {
    value: "Entry-level",
    focus: "Core skills, scoped delivery, and early production experience."
  },
  {
    value: "Mid-level",
    focus: "Independent delivery, production trade-offs, and ownership."
  },
  {
    value: "Senior",
    focus: "Architecture, technical leadership, and broad ownership."
  },
  {
    value: "Staff+",
    focus: "Cross-team influence, strategy, and organization-scale systems."
  }
] as const;

export function seniorityFocus(value: string): string {
  return SENIORITY_OPTIONS.find((option) => option.value === value)?.focus
    ?? "Questions and scoring are calibrated to the selected level.";
}
