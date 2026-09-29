// Deterministic, clearly fake sample results. Every string says "Sample" so mock output is never mistaken for AI output.
import type {
  AnswerFeedbackResult,
  ExperienceInput,
  ExperienceSuggestionsResult,
  JobFitResult,
  Question,
  ResumeScoreResult,
  SuggestionSource
} from "@/lib/api/types";

export function hash(text: string) {
  let value = 2166136261;
  for (let i = 0; i < text.length; i++) {
    value ^= text.charCodeAt(i);
    value = Math.imul(value, 16777619);
  }
  return value >>> 0;
}

export function normalize(text: string) {
  return text.trim().replace(/\s+/g, " ").toLowerCase();
}

const between = (seed: number, min: number, max: number) => min + (seed % (max - min + 1));

export function sampleResumeText(filename: string) {
  return `Sample extracted text for ${filename}\n\nEXPERIENCE\nSample Company — Software Engineer\n- Built sample services.\n\nSKILLS\nSample skill list`;
}

export function scoreResume(text: string, jobTitle: string | null, scoredAt: string): ResumeScoreResult {
  const seed = hash(normalize(text) + (jobTitle ?? ""));
  const scores = {
    technicalDepth: between(seed, 55, 90),
    impact: between(seed >>> 3, 45, 85),
    clarity: between(seed >>> 6, 60, 92),
    relevance: between(seed >>> 9, 50, 90),
    ats: between(seed >>> 12, 55, 88)
  };
  const overall = Math.round(Object.values(scores).reduce((a, b) => a + b, 0) / 5);
  return {
    overall,
    scores,
    summary: `Sample score summary${jobTitle ? ` for ${jobTitle}` : ""}: solid structure, impact needs numbers.`,
    fixes: [
      { rank: 1, section: "Experience", priority: "HIGH", message: "Sample fix: quantify the outcome of your most recent project." },
      { rank: 2, section: "Summary", priority: "MEDIUM", message: "Sample fix: open with the role you are targeting." },
      { rank: 3, section: "Skills", priority: "LOW", message: "Sample fix: group skills by area." }
    ],
    rewrites: [
      {
        section: "Experience",
        original: "Worked on backend services.",
        rewritten: "Sample rewrite: Led backend service work that cut response time by [X%] for [N] users.",
        placeholders: ["[X%]", "[N]"]
      }
    ],
    jobTitle,
    scoredAt
  };
}

export function jobFit(resumeText: string, jobText: string): JobFitResult {
  const seed = hash(normalize(resumeText) + normalize(jobText));
  return {
    fitScore: between(seed, 40, 92),
    summary: "Sample fit summary: good overlap on core skills, gaps in two listed requirements.",
    matchedRequirements: [
      { requirement: "Sample requirement: backend services", evidence: "Sample evidence from your resume." },
      { requirement: "Sample requirement: testing", evidence: "Sample evidence from your resume." }
    ],
    missingRequirements: [
      { requirement: "Sample requirement: event streaming", guidance: "Sample guidance: mention related work or how you would ramp up." }
    ],
    feedback: [
      { priority: "HIGH", message: "Sample feedback: move your most relevant project to the top." },
      { priority: "MEDIUM", message: "Sample feedback: mirror the job's wording for skills you have." }
    ]
  };
}

export function suggestions(sources: SuggestionSource[]): ExperienceSuggestionsResult {
  // Every third source set yields nothing, so the "no strong matches" state is reachable.
  if (sources.length === 0 || hash(sources.map((s) => s.id).join()) % 3 === 0) return { items: [] };
  return {
    items: sources.slice(0, 3).map((source) => ({
      requirement: "Sample requirement from the job description",
      source,
      match: `Sample match from "${source.name}".`,
      whyItFits: "Sample reason: it shows the skill the job asks for.",
      guidance: "Sample guidance: add it under your most recent role and lead with the result."
    }))
  };
}

const QUESTION_BANK = [
  ["Technical depth", "Walk me through a system you designed end to end."],
  ["Ownership", "Tell me about a production issue you owned from alert to fix."],
  ["Collaboration", "Describe a disagreement with a teammate and how you resolved it."],
  ["Impact", "What is the most measurable result you have delivered?"],
  ["Trade-offs", "Tell me about a trade-off you made under time pressure."],
  ["Learning", "How did you ramp up on an unfamiliar codebase?"],
  ["Quality", "How do you decide what to test?"],
  ["Communication", "Explain a complex technical topic to a non-engineer."]
] as const;

export function practiceQuestions(jobText: string, ids: () => string): Question[] {
  const count = between(hash(normalize(jobText)), 3, 8);
  return QUESTION_BANK.slice(0, count).map(([category, text], index) => ({
    id: ids(),
    order: index + 1,
    origin: "AI",
    text: `Sample: ${text}`,
    rationale: `Sample reason: the job description emphasizes ${category.toLowerCase()}.`,
    category,
    expectedSignals: ["Sample signal: concrete example", "Sample signal: measurable result"],
    attempts: []
  }));
}

export function answerFeedback(answer: string): AnswerFeedbackResult {
  // Longer answers score higher, so a revised answer usually shows a positive change.
  const score = Math.min(95, 40 + Math.floor(answer.trim().length / 12) + (hash(answer) % 6));
  return {
    score,
    summary: "Sample feedback: clear situation, add a measurable result.",
    nextStep: "Sample next step: add before and after numbers.",
    strengths: ["Sample strength: clear ownership"],
    gaps: ["Sample gap: no metric"],
    betterAnswerOutline: ["Context", "Action", "Measured result"],
    followUpQuestion: "Sample follow-up: how did you verify the result?"
  };
}

export function splitLinkedIn(text: string): ExperienceInput[] {
  const blocks = text.split(/\n\s*\n/).map((block) => block.trim()).filter(Boolean).slice(0, 30);
  return blocks.map((block) => {
    const [first, ...rest] = block.split("\n");
    return {
      title: first.slice(0, 120),
      organization: null,
      startDate: null,
      endDate: null,
      description: (rest.join(" ").trim() || first).slice(0, 4000)
    };
  });
}
