import React, { createRef } from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { InterviewPracticePanel } from "@/components/InterviewPracticePanel";
import { EvidenceRefs } from "@/components/EvidenceRefs";
import { ResumeInputPanel } from "@/components/ResumeInputPanel";
import type { InterviewQuestion } from "@/lib/mockAssessment";

const questions: InterviewQuestion[] = [
  {
    id: "question-a",
    category: "Architecture",
    difficulty: "Core",
    questionText: "Explain service A.",
    expectedSignals: ["tradeoffs"]
  },
  {
    id: "question-b",
    category: "Debugging",
    difficulty: "Deep Dive",
    questionText: "Explain service B.",
    expectedSignals: ["evidence"]
  }
];

describe("pending workflow controls", () => {
	  it("offers an Intern level with its interview calibration", () => {
	    render(
	      <ResumeInputPanel
	        uploadedResume={null}
	        extractionProgress={null}
	        elapsedSeconds={0}
	        targetRole="Software Engineer"
	        seniority="Intern"
	        resumeText="resume"
	        jobDescription="job"
	        analysisNotice={null}
	        analysisStage={null}
	        analysisElapsedSeconds={0}
	        isAnalyzing={false}
	        isUploadingResume={false}
	        resumeTextareaRef={createRef<HTMLTextAreaElement>()}
	        onResumeUpload={vi.fn()}
	        onRecoverLatestResume={vi.fn()}
	        onTargetRoleChange={vi.fn()}
	        onSeniorityChange={vi.fn()}
	        onResumeTextChange={vi.fn()}
	        onJobDescriptionChange={vi.fn()}
	        onRunAssessment={vi.fn()}
	      />
	    );

	    expect(screen.getByLabelText("Seniority")).toHaveValue("Intern");
	    expect(screen.getByRole("option", { name: "Intern" })).toBeInTheDocument();
	    expect(screen.getByText(/Fundamentals, projects, coursework/)).toBeInTheDocument();
	  });

  it("locks every analysis source control while analysis is running", () => {
    const { container } = render(
      <ResumeInputPanel
        uploadedResume={null}
        extractionProgress={null}
        elapsedSeconds={0}
        targetRole="Backend Engineer"
        seniority="Mid-level"
        resumeText="resume"
        jobDescription="job"
        analysisNotice={null}
        analysisStage={null}
        analysisElapsedSeconds={0}
        isAnalyzing
        isUploadingResume={false}
        resumeTextareaRef={createRef<HTMLTextAreaElement>()}
        onResumeUpload={vi.fn()}
        onRecoverLatestResume={vi.fn()}
        onTargetRoleChange={vi.fn()}
        onSeniorityChange={vi.fn()}
        onResumeTextChange={vi.fn()}
        onJobDescriptionChange={vi.fn()}
        onRunAssessment={vi.fn()}
      />
    );

    expect(screen.getByLabelText("Target role")).toBeDisabled();
    expect(screen.getByLabelText("Seniority")).toBeDisabled();
    expect(screen.getByLabelText("Resume text")).toBeDisabled();
    expect(screen.getByLabelText("Job description")).toBeDisabled();
    expect(container.querySelector('input[type="file"]')).toBeDisabled();
  });

  it("prevents changing the question or answer while feedback is pending", () => {
    const onActiveQuestionChange = vi.fn();
    const onAnswerChange = vi.fn();
    render(
      <InterviewPracticePanel
        questions={questions}
        activeQuestion={questions[0]}
        answer="submitted answer"
        answerFeedback={null}
        isSubmittingAnswer
        onActiveQuestionChange={onActiveQuestionChange}
        onAnswerChange={onAnswerChange}
        onSubmitAnswer={vi.fn()}
      />
    );

    const questionButtons = screen.getAllByRole("button", { name: /Architecture|Debugging/ });
    questionButtons.forEach((button) => expect(button).toBeDisabled());
    expect(screen.getByPlaceholderText("Type your answer here.")).toBeDisabled();
    fireEvent.click(questionButtons[1]);
    expect(onActiveQuestionChange).not.toHaveBeenCalled();
    expect(onAnswerChange).not.toHaveBeenCalled();
  });
});

describe("evidence references", () => {
  it("shows named context sections and collapses duplicate chunks", () => {
    render(
      <EvidenceRefs
        ids={[
          "resume:experience:4",
          "resume:skills:2",
          "resume:experience:3",
          "job_description:requirements:1"
        ]}
      />
    );

    expect(screen.getByText("Experience")).toBeInTheDocument();
    expect(screen.getByText("Skills")).toBeInTheDocument();
    expect(screen.getByText("Job description: Requirements")).toBeInTheDocument();
    expect(screen.getAllByText("Experience")).toHaveLength(1);
    expect(screen.queryByText("resume:4")).not.toBeInTheDocument();
  });

  it("resolves legacy numeric IDs to explicit resume sections", () => {
    const resumeText = `SUMMARY
Backend engineer

PROFESSIONAL EXPERIENCE
Built backend services

PERSONAL PROJECTS
Created an interview coach

TECHNICAL SKILLS
Java, PostgreSQL, Redis

EDUCATION
Bachelor of Science`;

    render(
      <EvidenceRefs
        ids={["resume:4", "resume:2", "resume:3", "resume:1", "resume:0"]}
        resumeText={resumeText}
      />
    );

    ["Summary", "Experience", "Projects", "Skills", "Education"].forEach((section) => {
      expect(screen.getByText(section)).toBeInTheDocument();
    });
    expect(screen.queryByText("Resume")).not.toBeInTheDocument();
  });

  it("detects a project heading embedded in a legacy experience chunk", () => {
    render(
      <EvidenceRefs
        ids={["resume:3", "resume:4"]}
        resumeChunks={[
          { index: 3, section: "Experience", content: "Research Assistant\nBuilt a pipeline" },
          {
            index: 4,
            section: "Experience",
            content: "PERSONAL PROJECTS\nAI Interview Coach\nBuilt a RAG pipeline"
          }
        ]}
      />
    );

    expect(screen.getByText("Experience")).toBeInTheDocument();
    expect(screen.getByText("Projects")).toBeInTheDocument();
  });

  it("uses the question text to select experience from a mixed legacy chunk", () => {
    render(
      <EvidenceRefs
        ids={["resume:4"]}
        resumeChunks={[
          {
            index: 4,
            section: "Experience",
            content: `Developed RESTful APIs for volunteer registration and tracking workflows, cutting processing time by 67%.

PERSONAL PROJECTS
Built an AI interview coach with a RAG pipeline.`
          }
        ]}
        evidenceHint="How would you debug the volunteer registration API under high load?"
      />
    );

    expect(screen.getByText("Experience")).toBeInTheDocument();
    expect(screen.queryByText("Projects")).not.toBeInTheDocument();
  });

  it("uses the question text to select projects from the same mixed chunk", () => {
    render(
      <EvidenceRefs
        ids={["resume:4"]}
        resumeChunks={[
          {
            index: 4,
            section: "Experience",
            content: `Developed RESTful APIs for volunteer registration workflows.

PERSONAL PROJECTS
Built an AI interview coach with a RAG pipeline.`
          }
        ]}
        evidenceHint="Explain the RAG pipeline in your AI interview coach."
      />
    );

    expect(screen.getByText("Projects")).toBeInTheDocument();
    expect(screen.queryByText("Experience")).not.toBeInTheDocument();
  });
});
