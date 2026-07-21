import React from "react";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import Home from "@/app/page";
import { createAiAnalysis, createAiAnswerFeedback } from "@/lib/api/ai";
import {
  getJob,
  JobAcceptedResponse,
  JobStatus,
  JobStatusResponse
} from "@/lib/api/jobs";
import { persistJobWorkflow } from "@/lib/jobWorkflow";
import { createAssessment } from "@/lib/mockAssessment";
import { getCurrentResume } from "@/lib/api/resumes";

vi.mock("@/lib/api/ai", () => ({
  createAiAnalysis: vi.fn(),
  createAiAnswerFeedback: vi.fn()
}));

vi.mock("@/lib/api/resumes", () => ({
  getCurrentResume: vi.fn(),
  uploadResume: vi.fn()
}));

vi.mock("@/lib/api/jobs", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/jobs")>();
  return { ...actual, getJob: vi.fn() };
});

const accepted: JobAcceptedResponse = {
  jobId: "analysis-job",
  jobType: "ANALYSIS",
  status: "QUEUED",
  stage: "QUEUED",
  statusUrl: "/api/jobs/analysis-job",
  reused: false,
  inputRefs: { resumeId: "accepted-resume-id", jobDescriptionId: null }
};

describe("analysis workflow", () => {
  beforeEach(() => {
    vi.mocked(getJob).mockReset();
    vi.mocked(createAiAnalysis).mockReset();
    vi.mocked(createAiAnswerFeedback).mockReset();
    vi.mocked(getCurrentResume).mockResolvedValue(null);
  });

  it("uses the restored submission snapshot for a failed-job fallback", async () => {
    const snapshot = {
      resumeText: "Kubernetes platform ownership",
      jobDescription: "GCP reliability engineering",
      targetRole: "Platform Engineer",
      seniority: "Senior"
    };
    persistJobWorkflow(
      "ai-interview:job:analysis",
      accepted,
      snapshot,
      { local: window.localStorage, session: window.sessionStorage },
      "analysis-generation"
    );
    vi.mocked(getCurrentResume).mockResolvedValue(
      resumeResponse("newer-resume", "A current resume must not overwrite an active restored workflow")
    );
    vi.mocked(getJob).mockResolvedValue(job("FAILED", null, "Gemini failed"));

    render(<Home />);

    expect(await screen.findByText(/Local draft results are shown/)).toBeInTheDocument();
    expect(screen.getByText("Platform Engineer · Senior")).toBeInTheDocument();
    expect(screen.getByLabelText("Target role")).toHaveValue("Platform Engineer");
    expect(screen.getByLabelText("Seniority")).toHaveValue("Senior");
    expect(screen.getByLabelText("Resume text")).toHaveValue(snapshot.resumeText);
    expect(screen.getByLabelText("Job description")).toHaveValue(snapshot.jobDescription);
    fireEvent.click(screen.getByRole("button", { name: /System Design/ }));
    expect(screen.getByText(/using kubernetes, gcp when traffic grows by 10x/i)).toBeInTheDocument();
  });

  it("does not build a failed-job fallback from current fields when the snapshot is missing", async () => {
    persistJobWorkflow(
      "ai-interview:job:analysis",
      accepted,
      null,
      { local: window.localStorage, session: window.sessionStorage },
      "analysis-generation"
    );
    vi.mocked(getJob).mockResolvedValue(job("FAILED", null, "Gemini failed"));

    render(<Home />);

    expect(await screen.findByText(/original input snapshot is unavailable/i)).toBeInTheDocument();
    expect(screen.getByText("No assessment yet")).toBeInTheDocument();
    expect(screen.getByText("Questions are waiting")).toBeInTheDocument();
  });

  it("fills an empty successful question set locally and invalidates it after source edits", async () => {
    const assessment = createAssessment("Java Spring resume", "Backend role");
    vi.mocked(createAiAnalysis).mockResolvedValue(accepted);
    vi.mocked(getJob).mockResolvedValue(job("SUCCEEDED", {
      assessment,
      questions: { questions: [], modelProvider: "gemini" }
    }));

    render(<Home />);
    fireEvent.click(screen.getByRole("button", { name: /Analyze resume/ }));

    expect(await screen.findByText(/Gemini returned no usable questions/i)).toBeInTheDocument();
    expect(screen.getByText("4 questions")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Target role"), { target: { value: "Platform Engineer" } });

    await waitFor(() => expect(screen.getByText("No assessment yet")).toBeInTheDocument());
    expect(screen.getByText("Questions are waiting")).toBeInTheDocument();
  });

  it("uses a loaded resume ID until the user edits the resume text", async () => {
    vi.mocked(getCurrentResume).mockResolvedValue(resumeResponse("loaded-resume-id", "Loaded backend resume"));
    vi.mocked(createAiAnalysis).mockResolvedValue(accepted);
    vi.mocked(getJob).mockResolvedValue(job("SUCCEEDED", {
      assessment: createAssessment("Loaded backend resume", ""),
      questions: { questions: [], modelProvider: "gemini" }
    }));

    render(<Home />);
    await waitFor(() => expect(screen.getByLabelText("Resume text")).toHaveValue("Loaded backend resume"));
    fireEvent.change(screen.getByLabelText("Resume text"), {
      target: { value: "Edited inline resume" }
    });
    fireEvent.click(screen.getByRole("button", { name: /Analyze resume/ }));

    await waitFor(() => expect(createAiAnalysis).toHaveBeenCalled());
    expect(vi.mocked(createAiAnalysis).mock.calls[0][0]).toMatchObject({
      resumeId: null,
      resumeText: "Edited inline resume"
    });
  });

  it("captures materialized document IDs for the next analysis", async () => {
    vi.mocked(getCurrentResume).mockResolvedValue(resumeResponse("loaded-resume-id", "Loaded backend resume"));
    vi.mocked(createAiAnalysis).mockResolvedValue({
      ...accepted,
      inputRefs: { resumeId: "loaded-resume-id", jobDescriptionId: "materialized-jd-id" }
    });
    vi.mocked(getJob).mockResolvedValue(job("SUCCEEDED", {
      assessment: createAssessment("Loaded backend resume", "Java role"),
      questions: { questions: [], modelProvider: "gemini" }
    }, undefined, { resumeId: "loaded-resume-id", jobDescriptionId: "materialized-jd-id" }));

    render(<Home />);
    await waitFor(() => expect(screen.getByLabelText("Resume text")).toHaveValue("Loaded backend resume"));
    fireEvent.change(screen.getByLabelText("Job description"), { target: { value: "Java role" } });
    fireEvent.click(screen.getByRole("button", { name: /Analyze resume/ }));
    await screen.findByText(/Gemini returned no usable questions/i);
    fireEvent.click(screen.getByRole("button", { name: /Refresh analysis/ }));

    await waitFor(() => expect(createAiAnalysis).toHaveBeenCalledTimes(2));
    expect(vi.mocked(createAiAnalysis).mock.calls[1][0]).toMatchObject({
      resumeId: "loaded-resume-id",
      jobDescriptionId: "materialized-jd-id",
      jobDescription: "Java role"
    });
  });

  it("keeps a successful partial assessment and locally fills only missing questions", async () => {
    const providerAssessment = {
      ...createAssessment("Java resume", "Backend role"),
      overallScore: 99
    };
    vi.mocked(createAiAnalysis).mockResolvedValue(accepted);
    vi.mocked(getJob).mockResolvedValue(job("PARTIAL", {
      assessment: providerAssessment,
      questions: null
    }, "question generation failed"));

    render(<Home />);
    fireEvent.click(screen.getByRole("button", { name: /Analyze resume/ }));

    expect(await screen.findByLabelText("Overall score 99")).toBeInTheDocument();
    expect(screen.getByText(/local draft questions fill the missing result/i)).toBeInTheDocument();
    expect(screen.getByText("4 questions")).toBeInTheDocument();
  });

  it("labels legacy evidence IDs from the loaded resume chunks", async () => {
    const normalizedText = `Jiaming Zhang

EDUCATION
Bachelor of Science

TECHNICAL SKILLS
Java and PostgreSQL

PROFESSIONAL EXPERIENCE
Research Assistant

PERSONAL PROJECTS
AI Interview Coach`;
    vi.mocked(getCurrentResume).mockResolvedValue({
      id: "resume-id",
      originalFilename: "resume.pdf",
      contentType: "application/pdf",
      detectedContentType: "application/pdf",
      sizeBytes: 1000,
      rawTextLength: normalizedText.length,
      normalizedTextLength: normalizedText.length,
      normalizedText,
      chunks: [
        { index: 0, section: "Summary", content: "Jiaming Zhang", characterCount: 13 },
        { index: 1, section: "Education", content: "Bachelor of Science", characterCount: 19 },
        { index: 2, section: "Skills", content: "Java and PostgreSQL", characterCount: 19 },
        { index: 3, section: "Experience", content: "Research Assistant", characterCount: 18 },
        {
          index: 4,
          section: "Experience",
          content: "PERSONAL PROJECTS\nAI Interview Coach",
          characterCount: 36
        }
      ],
      processedAt: new Date().toISOString()
    });
    vi.mocked(createAiAnalysis).mockResolvedValue(accepted);
    vi.mocked(getJob).mockResolvedValue(job("SUCCEEDED", {
      assessment: {
        ...createAssessment(normalizedText, ""),
        sourceContextIds: ["resume:4", "resume:2", "resume:3", "resume:1", "resume:0"]
      },
      questions: { questions: [], modelProvider: "gemini" }
    }));

    render(<Home />);
    await waitFor(() => expect(screen.getByLabelText("Resume text")).toHaveValue(normalizedText));
    fireEvent.click(screen.getByRole("button", { name: /Analyze resume/ }));

    const evidence = await screen.findByLabelText("Retrieved evidence");
    ["Summary", "Education", "Skills", "Experience", "Projects"].forEach((section) => {
      expect(within(evidence).getByText(section)).toBeInTheDocument();
    });
    expect(within(evidence).queryByText("Resume")).not.toBeInTheDocument();
  });

  it("keeps completed feedback attached to its submitted question", async () => {
    const feedbackResult = deferred<JobStatusResponse<{
      score: number;
      summary: string;
      nextStep: string;
    }>>();
    const questions = [
      {
        id: "question-a",
        category: "Question A",
        difficulty: "Core" as const,
        questionText: "Explain A.",
        expectedSignals: ["A"]
      },
      {
        id: "question-b",
        category: "Question B",
        difficulty: "Deep Dive" as const,
        questionText: "Explain B.",
        expectedSignals: ["B"]
      }
    ];
    vi.mocked(createAiAnalysis).mockResolvedValue(accepted);
    vi.mocked(createAiAnswerFeedback).mockResolvedValue({
      ...accepted,
      jobId: "feedback-job",
      jobType: "ANSWER_FEEDBACK",
      statusUrl: "/api/jobs/feedback-job"
    });
    vi.mocked(getJob).mockImplementation((jobId) => {
      if (jobId === "feedback-job") {
        return feedbackResult.promise;
      }
      return Promise.resolve(job("SUCCEEDED", {
        assessment: createAssessment("Java resume", "Backend role"),
        questions: { questions, modelProvider: "gemini" }
      }));
    });

    render(<Home />);
    fireEvent.click(screen.getByRole("button", { name: /Analyze resume/ }));
    await screen.findByText("Explain A.");
    fireEvent.change(screen.getByPlaceholderText("Type your answer here."), {
      target: { value: "A detailed submitted answer" }
    });
    fireEvent.click(screen.getByRole("button", { name: /Get feedback/ }));

    const questionB = screen.getByRole("button", { name: /Question B/ });
    await waitFor(() => expect(questionB).toBeDisabled());
    await act(async () => {
      feedbackResult.resolve(job("SUCCEEDED", {
        score: 91,
        summary: "Feedback for A",
        nextStep: "Improve A"
      }));
    });
    await screen.findByText("Feedback for A");

    fireEvent.click(questionB);
    expect(screen.queryByText("Feedback for A")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /Question A/ }));
    expect(screen.getByText("Feedback for A")).toBeInTheDocument();
  });

	  it("uses document references learned from job status when submitting feedback", async () => {
	    const questions = [{
	      id: "question-a",
	      category: "Architecture",
	      difficulty: "Core" as const,
	      questionText: "Explain the architecture.",
	      expectedSignals: ["tradeoffs"]
	    }];
	    vi.mocked(createAiAnalysis).mockResolvedValue({
	      ...accepted,
	      inputRefs: { resumeId: null, jobDescriptionId: null }
	    });
	    vi.mocked(createAiAnswerFeedback).mockResolvedValue({
	      ...accepted,
	      jobId: "feedback-job",
	      jobType: "ANSWER_FEEDBACK",
	      statusUrl: "/api/jobs/feedback-job",
	      inputRefs: { resumeId: "status-resume-id", jobDescriptionId: "status-jd-id" }
	    });
	    vi.mocked(getJob).mockImplementation((jobId) => Promise.resolve(
	      jobId === "analysis-job"
	        ? job("SUCCEEDED", {
	            assessment: createAssessment("Java resume", "Backend role"),
	            questions: { questions, modelProvider: "gemini" }
	          }, undefined, { resumeId: "status-resume-id", jobDescriptionId: "status-jd-id" })
	        : job("QUEUED", null, undefined, {
	            resumeId: "status-resume-id",
	            jobDescriptionId: "status-jd-id"
	          })
	    ));

	    render(<Home />);
	    fireEvent.change(screen.getByLabelText("Job description"), { target: { value: "Backend role" } });
	    fireEvent.click(screen.getByRole("button", { name: /Analyze resume/ }));
	    await screen.findByText("Explain the architecture.");
	    fireEvent.change(screen.getByPlaceholderText("Type your answer here."), {
	      target: { value: "I would compare consistency and availability tradeoffs." }
	    });
	    fireEvent.click(screen.getByRole("button", { name: /Get feedback/ }));

	    await waitFor(() => expect(createAiAnswerFeedback).toHaveBeenCalled());
	    expect(vi.mocked(createAiAnswerFeedback).mock.calls[0][0]).toMatchObject({
	      resumeId: "status-resume-id",
	      jobDescriptionId: "status-jd-id"
	    });
	  });
});

function job<TResult>(
  status: JobStatus,
  result: TResult,
  errorMessage?: string,
  inputRefs = { resumeId: "accepted-resume-id", jobDescriptionId: null as string | null }
): JobStatusResponse<TResult> {
  return {
    jobId: "analysis-job",
    jobType: "ANALYSIS",
    status,
    stage: "COMPLETED",
    attempts: 1,
    result,
    error: errorMessage ? { code: "AI_ERROR", message: errorMessage, retryable: false } : null,
    createdAt: new Date().toISOString(),
    startedAt: new Date().toISOString(),
    completedAt: new Date().toISOString(),
    inputRefs
  };
}

function resumeResponse(id: string, normalizedText: string) {
  return {
    id,
    originalFilename: "resume.pdf",
    contentType: "application/pdf",
    detectedContentType: "application/pdf",
    sizeBytes: 1000,
    rawTextLength: normalizedText.length,
    normalizedTextLength: normalizedText.length,
    normalizedText,
    chunks: [],
    processedAt: new Date().toISOString()
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((nextResolve, nextReject) => {
    resolve = nextResolve;
    reject = nextReject;
  });
  return { promise, resolve, reject };
}
