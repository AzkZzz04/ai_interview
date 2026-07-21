import { afterEach, describe, expect, it, vi } from "vitest";
import {
  AiAnalysisPayload,
  analysisRequest,
  createAiAnalysis,
  feedbackRequest
} from "@/lib/api/ai";
import { JobApiError } from "@/lib/api/jobs";

const inline: AiAnalysisPayload = {
  resumeId: null,
  resumeText: "private resume",
  jobDescriptionId: null,
  jobDescription: "private job description",
  targetRole: "Backend Engineer",
  seniority: "Senior"
};

describe("AI API document references", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("sends exactly one of ID or text for each document", () => {
    const request = analysisRequest({
      ...inline,
      resumeId: "resume-id",
      jobDescriptionId: "jd-id"
    });

    expect(request).toMatchObject({ resumeId: "resume-id", jobDescriptionId: "jd-id" });
    expect(request).not.toHaveProperty("resumeText");
    expect(request).not.toHaveProperty("jobDescription");
  });

  it("sends inline text when IDs are unavailable and omits an empty JD", () => {
    expect(analysisRequest({ ...inline, jobDescription: "" })).toEqual({
      resumeText: "private resume",
      targetRole: "Backend Engineer",
      seniority: "Senior"
    });
  });

  it("uses the same one-of rule for feedback", () => {
    const request = feedbackRequest({
      ...inline,
      resumeId: "resume-id",
      questionText: "How did you design it?",
      category: "Architecture",
      expectedSignals: ["tradeoffs"],
      answerText: "I separated the workflow."
    });

    expect(request.resumeId).toBe("resume-id");
    expect(request).not.toHaveProperty("resumeText");
    expect(request.jobDescription).toBe("private job description");
  });

  it("preserves structured HTTP error codes", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ code: "REFERENCE_MISMATCH", message: "changed server wording" }),
      { status: 409, headers: { "content-type": "application/json" } }
    )));

    const error = createAiAnalysis(inline).catch((caught) => caught);
    await expect(error).resolves.toEqual(expect.objectContaining<Partial<JobApiError>>({
      name: "JobApiError",
      status: 409,
      code: "REFERENCE_MISMATCH"
    }));
  });
});
