import React, { createRef } from "react";
import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { AssessmentPanel } from "@/components/AssessmentPanel";
import { InterviewPracticePanel } from "@/components/InterviewPracticePanel";
import { JobProgress } from "@/components/JobProgress";
import { ResumeInputPanel } from "@/components/ResumeInputPanel";

function renderInputPanel(overrides: Record<string, unknown> = {}) {
  const props = {
    uploadedResume: null,
    extractionProgress: null,
    elapsedSeconds: 0,
    targetRole: "Backend Engineer",
    seniority: "Mid-level",
    resumeText: "resume",
    jobDescription: "job",
    analysisNotice: null,
    analysisStage: null,
    analysisElapsedSeconds: 0,
    isAnalyzing: false,
    isUploadingResume: false,
    resumeTextareaRef: createRef<HTMLTextAreaElement>(),
    onResumeUpload: vi.fn(),
    onRecoverLatestResume: vi.fn(),
    onTargetRoleChange: vi.fn(),
    onSeniorityChange: vi.fn(),
    onResumeTextChange: vi.fn(),
    onJobDescriptionChange: vi.fn(),
    onRunAssessment: vi.fn(),
    ...overrides
  };
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return render(<ResumeInputPanel {...(props as any)} />);
}

describe("upload accessibility", () => {
  it("keeps the file input reachable instead of removing it from the a11y tree", () => {
    const { container } = renderInputPanel();
    const fileInput = container.querySelector('input[type="file"]') as HTMLInputElement;

    expect(fileInput).not.toBeNull();
    // `display: none` would drop the control out of the tab order entirely.
    expect(fileInput.hidden).toBe(false);
    expect(fileInput.getAttribute("aria-label")).toBe("Upload resume");
    expect(screen.getByLabelText("Upload resume")).toBe(fileInput);
  });
});

describe("job progress", () => {
  it("announces stage and elapsed time in a live region", () => {
    render(<JobProgress stage="Scoring the resume with Gemini" elapsedSeconds={12} label="Analysis running" />);

    const status = screen.getByRole("status");
    expect(status).toHaveAttribute("aria-live", "polite");
    expect(status).toHaveTextContent("Scoring the resume with Gemini");
    expect(status).toHaveTextContent("12s");
  });

  it("shows analysis progress on the same component the upload path uses", () => {
    renderInputPanel({
      isAnalyzing: true,
      analysisStage: "Generating interview questions",
      analysisElapsedSeconds: 8
    });

    const status = screen.getByRole("status");
    expect(status).toHaveTextContent("Generating interview questions");
    expect(status).toHaveTextContent("8s");
  });

  it("shows extraction progress for the upload path", () => {
    renderInputPanel({
      isUploadingResume: true,
      extractionProgress: { startedAt: Date.now(), stage: "Extracting document text" },
      elapsedSeconds: 3
    });

    const status = screen.getByRole("status");
    expect(status).toHaveTextContent("Extracting document text");
    expect(status).toHaveTextContent("3s");
  });
});

describe("analysis skeletons", () => {
  it("replaces the assessment empty state while analysis runs", () => {
    const { container, rerender } = render(
      <AssessmentPanel targetRole="Backend" seniority="Mid" assessment={null} isAnalyzing={false} />
    );
    expect(screen.getByText("No assessment yet")).toBeInTheDocument();

    rerender(<AssessmentPanel targetRole="Backend" seniority="Mid" assessment={null} isAnalyzing />);
    expect(screen.queryByText("No assessment yet")).not.toBeInTheDocument();
    expect(container.querySelector(".skeleton")).not.toBeNull();
  });

  it("replaces the interview empty state while analysis runs", () => {
    const { container, rerender } = render(
      <InterviewPracticePanel
        questions={[]}
        activeQuestion={undefined}
        answer=""
        answerFeedback={null}
        isSubmittingAnswer={false}
        isAnalyzing={false}
        onActiveQuestionChange={vi.fn()}
        onAnswerChange={vi.fn()}
        onSubmitAnswer={vi.fn()}
      />
    );
    expect(screen.getByText("Questions are waiting")).toBeInTheDocument();

    rerender(
      <InterviewPracticePanel
        questions={[]}
        activeQuestion={undefined}
        answer=""
        answerFeedback={null}
        isSubmittingAnswer={false}
        isAnalyzing
        onActiveQuestionChange={vi.fn()}
        onAnswerChange={vi.fn()}
        onSubmitAnswer={vi.fn()}
      />
    );
    expect(screen.queryByText("Questions are waiting")).not.toBeInTheDocument();
    expect(container.querySelector(".skeleton")).not.toBeNull();
  });
});
