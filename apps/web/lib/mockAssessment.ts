export type AssessmentScoreKey =
  | "technicalDepth"
  | "impact"
  | "clarity"
  | "relevance"
  | "ats";

export type Assessment = {
  overallScore: number;
  scores: Record<AssessmentScoreKey, number>;
  strengths: string[];
  weaknesses: string[];
  recommendations: Array<{
    section: string;
    priority: "high" | "medium" | "low";
    message: string;
  }>;
  modelProvider?: string;
  sourceContextIds?: string[];
};

export type InterviewQuestion = {
  id: string;
  category: string;
  difficulty: "Warmup" | "Core" | "Deep Dive";
  questionText: string;
  expectedSignals: string[];
  sourceContextIds?: string[];
};

export type AnswerFeedback = {
  score: number;
  summary: string;
  nextStep: string;
  strengths?: string[];
  gaps?: string[];
  betterAnswerOutline?: string[];
  followUpQuestion?: string;
  modelProvider?: string;
  sourceContextIds?: string[];
};

const technicalTerms = [
  "java",
  "spring",
  "postgres",
  "redis",
  "kubernetes",
  "aws",
  "gcp",
  "react",
  "next",
  "typescript",
  "microservices",
  "distributed",
  "latency",
  "observability",
  "security"
];

export function createAssessment(
  resumeText: string,
  jobDescription: string,
  seniority = "Mid-level"
): Assessment {
  const text = `${resumeText}\n${jobDescription}`.toLowerCase();
  const resumeOnly = resumeText.toLowerCase();
  const matchedTerms = technicalTerms.filter((term) => text.includes(term));
  const hasMetrics = /\b\d+(\.\d+)?(%|x|ms|s|k|m| users| requests| qps| rps| gb| tb)\b/i.test(
    resumeText
  );
  const hasRoleMatch = jobDescription.trim().length === 0 || overlapScore(resumeText, jobDescription) > 0.08;
  const hasClearSections = ["experience", "skills", "education"].filter((section) =>
    resumeOnly.includes(section)
  ).length;

  const technicalDepth = clamp(55 + matchedTerms.length * 3, 45, 92);
  const impact = hasMetrics ? 82 : 62;
  const clarity = clamp(58 + hasClearSections * 8, 50, 88);
  const relevance = hasRoleMatch ? clamp(68 + matchedTerms.length * 2, 55, 91) : 56;
  const ats = clamp(60 + hasClearSections * 7 + (resumeText.length > 1200 ? 6 : 0), 52, 90);
  const overallScore = Math.round((technicalDepth + impact + clarity + relevance + ats) / 5);
  const isIntern = seniority.trim().toLowerCase() === "intern";

  return {
    overallScore,
    scores: {
      technicalDepth,
      impact,
      clarity,
      relevance,
      ats
    },
    strengths: [
      matchedTerms.length > 3
        ? "Strong technical keyword coverage for backend and platform screening"
        : "Clear base experience for a technical resume review",
      hasClearSections > 1 ? "Resume structure is parseable across core sections" : "Core experience is visible"
    ],
    weaknesses: [
      hasMetrics
        ? isIntern
          ? "Some project claims still need clearer technical decisions and learning outcomes"
          : "Some technical claims still need more ownership and tradeoff detail"
        : "Impact is under-supported because bullets do not include measurable outcomes",
      hasRoleMatch
        ? "Role alignment can improve with more explicit requirement coverage"
        : "Job description alignment is weak or not yet provided"
    ],
    recommendations: [
      {
        section: "Experience",
        priority: "high",
        message:
          "Rewrite the strongest project bullet with scale, constraint, action, and measurable result."
      },
      {
        section: "Skills",
        priority: "medium",
        message:
          "Group languages, frameworks, databases, infrastructure, and observability tools for faster scanning."
      },
      {
        section: "Projects",
        priority: "medium",
        message: isIntern
          ? "Add one project bullet explaining the problem, your implementation choice, how you tested it, and what you learned."
          : "Add one architecture-focused bullet that explains system boundaries, data flow, and production tradeoffs."
      }
    ]
  };
}

export function createQuestions(
  resumeText: string,
  jobDescription: string,
  seniority = "Mid-level"
): InterviewQuestion[] {
  const stack = technicalTerms.filter((term) => `${resumeText} ${jobDescription}`.toLowerCase().includes(term));
  const primaryStack = stack.slice(0, 3).join(", ") || "your primary stack";

  if (seniority.trim().toLowerCase() === "intern") {
    return createInternQuestions(primaryStack);
  }

  return [
    {
      id: "resume-deep-dive",
      category: "Resume Deep Dive",
      difficulty: "Warmup",
      questionText:
        "Choose the most technically complex project on your resume. What problem did it solve, and what was your direct ownership?",
      expectedSignals: ["clear project context", "personal ownership", "technical constraints", "measured result"]
    },
    {
      id: "architecture",
      category: "System Design",
      difficulty: "Deep Dive",
      questionText: `Design how you would evolve a service using ${primaryStack} when traffic grows by 10x.`,
      expectedSignals: ["bottleneck identification", "data model choices", "caching", "observability", "failure modes"]
    },
    {
      id: "debugging",
      category: "Production Debugging",
      difficulty: "Core",
      questionText:
        "A previously stable endpoint becomes slow after a release. Walk through your debugging process from alert to rollback or fix.",
      expectedSignals: ["hypothesis-driven debugging", "logs and traces", "database checks", "risk control"]
    },
    {
      id: "behavioral",
      category: "Collaboration",
      difficulty: "Core",
      questionText:
        "Tell me about a time you disagreed with a technical direction. How did you evaluate tradeoffs and move the team forward?",
      expectedSignals: ["specific conflict", "tradeoff reasoning", "communication", "outcome"]
    }
  ];
}

function createInternQuestions(primaryStack: string): InterviewQuestion[] {
  return [
    {
      id: "intern-project-overview",
      category: "Resume Deep Dive",
      difficulty: "Warmup",
      questionText: "Choose one project you understand well. What problem did it solve, what did you build, and what was your contribution?",
      expectedSignals: ["clear project goal", "personal contribution", "technical approach"]
    },
    {
      id: "intern-fundamentals",
      category: "Technical Fundamentals",
      difficulty: "Warmup",
      questionText: "Describe a data structure you used in a project and explain why it fit the problem.",
      expectedSignals: ["correct fundamentals", "time and space trade-off", "project connection"]
    },
    {
      id: "intern-learning",
      category: "Learning",
      difficulty: "Warmup",
      questionText: `Tell me how you learned one part of ${primaryStack} well enough to use it in a project.`,
      expectedSignals: ["learning process", "credible resources", "application and reflection"]
    },
    {
      id: "intern-collaboration",
      category: "Collaboration",
      difficulty: "Warmup",
      questionText: "Tell me about a time you asked for feedback or helped a teammate unblock a project.",
      expectedSignals: ["clear situation", "communication", "coachability", "outcome"]
    },
    {
      id: "intern-debugging",
      category: "Debugging",
      difficulty: "Core",
      questionText: "A feature works locally but fails in a shared environment. How would you narrow down the cause?",
      expectedSignals: ["reproduce the issue", "inspect logs and configuration", "test one hypothesis at a time"]
    },
    {
      id: "intern-testing",
      category: "Testing",
      difficulty: "Core",
      questionText: "How would you test the most important behavior in one of your projects?",
      expectedSignals: ["critical behavior", "unit and integration boundaries", "edge cases"]
    },
    {
      id: "intern-role-tooling",
      category: "Role-Specific Tooling",
      difficulty: "Core",
      questionText: `Pick one technology from ${primaryStack}. Explain how it works in your project and one trade-off you encountered.`,
      expectedSignals: ["accurate explanation", "hands-on usage", "bounded trade-off"]
    },
    {
      id: "intern-bounded-design",
      category: "Project Architecture",
      difficulty: "Deep Dive",
      questionText: "Take one project on your resume and redesign one component to support more users while keeping the scope manageable.",
      expectedSignals: ["current bottleneck", "simple component boundaries", "data flow", "testing and monitoring"]
    }
  ];
}

export function scoreAnswer(answer: string, seniority = "Mid-level"): AnswerFeedback {
  const hasStructure = /(first|second|finally|because|tradeoff|result)/i.test(answer);
  const hasDetail = answer.length > 280;
  const hasMetrics = /\d/.test(answer);
  const isIntern = seniority.trim().toLowerCase() === "intern";
  const score = clamp(48 + (hasStructure ? 18 : 0) + (hasDetail ? 18 : 0) + (hasMetrics ? 10 : 0), 40, 94);

  return {
    score,
    summary:
      score > 78
        ? isIntern
          ? "Strong answer shape. Add one clearer technical reason and what you learned to make it interview-ready."
          : "Strong answer shape. Add one sharper technical tradeoff to make it interview-ready."
        : "The answer needs more structure, concrete technical detail, and a clearer outcome.",
    nextStep: isIntern
      ? "Use problem, approach, technical reason, test, result, and learning as the answer spine."
      : "Use context, action, tradeoff, result, and follow-up learning as the answer spine."
  };
}

function overlapScore(left: string, right: string) {
  const leftWords = toWordSet(left);
  const rightWords = toWordSet(right);
  if (leftWords.size === 0 || rightWords.size === 0) {
    return 0;
  }
  let overlap = 0;
  leftWords.forEach((word) => {
    if (rightWords.has(word)) {
      overlap += 1;
    }
  });
  return overlap / Math.max(leftWords.size, rightWords.size);
}

function toWordSet(value: string) {
  return new Set(
    value
      .toLowerCase()
      .split(/[^a-z0-9+#.]+/)
      .filter((word) => word.length > 2)
  );
}

function clamp(value: number, min: number, max: number) {
  return Math.max(min, Math.min(max, Math.round(value)));
}
