import { describe, expect, it } from "vitest";

import { createAssessment, createQuestions, scoreAnswer } from "@/lib/mockAssessment";
import { SENIORITY_OPTIONS, seniorityFocus } from "@/lib/seniority";

describe("Intern seniority settings", () => {
  it("is exposed as a supported seniority option", () => {
    expect(SENIORITY_OPTIONS[0].value).toBe("Intern");
    expect(seniorityFocus("Intern")).toContain("coursework");
  });

  it("uses an intern-specific fallback question mix", () => {
    const questions = createQuestions("PROJECTS\nBuilt a Java API", "Software engineering intern", "Intern");

    expect(questions).toHaveLength(8);
    expect(questions.filter((question) => question.difficulty === "Warmup")).toHaveLength(4);
    expect(questions.filter((question) => question.difficulty === "Core")).toHaveLength(3);
    expect(questions.filter((question) => question.difficulty === "Deep Dive")).toHaveLength(1);
    expect(questions.map((question) => question.questionText).join(" ")).not.toMatch(/organization-wide|staff-level/i);
  });

  it("keeps fallback recommendations appropriate for an intern", () => {
    const assessment = createAssessment("PROJECTS\nBuilt and tested a Java API", "Software engineering intern", "Intern");

    expect(assessment.recommendations).toContainEqual(expect.objectContaining({
      section: "Projects",
      message: expect.stringContaining("what you learned")
    }));
  });

  it("uses learning-oriented fallback answer feedback", () => {
    const feedback = scoreAnswer("First I built it, then I tested the result.", "Intern");

    expect(feedback.nextStep).toContain("technical reason");
    expect(feedback.nextStep).toContain("learning");
  });
});
