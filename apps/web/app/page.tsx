"use client";

import {
  FileText,
  Gauge,
  Loader2,
  MessageSquareText,
  RefreshCw,
  Sparkles
} from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { AssessmentPanel } from "@/components/AssessmentPanel";
import { InterviewPracticePanel } from "@/components/InterviewPracticePanel";
import { ResumeInputPanel } from "@/components/ResumeInputPanel";
import {
  AiAnalysisPayload,
  AnswerFeedbackPayload,
  createAiAnalysis,
  createAiAnswerFeedback
} from "@/lib/api/ai";
import type { JobInputRefs } from "@/lib/api/jobs";
import type { ResumeUploadResponse } from "@/lib/api/resumes";
import { friendlyError } from "@/lib/errorMessages";
import { createGenerationToken } from "@/lib/jobWorkflow";
import { useScrollSpy } from "@/lib/useScrollSpy";
import {
  AnswerFeedback,
  Assessment,
  createAssessment,
  createQuestions,
  InterviewQuestion,
  scoreAnswer
} from "@/lib/mockAssessment";
import type {
  PendingFeedbackInfo,
  ResumeEvidenceSource
} from "@/lib/workflows/types";
import { useAnalysisWorkflow } from "@/lib/workflows/useAnalysisWorkflow";
import { useFeedbackWorkflow } from "@/lib/workflows/useFeedbackWorkflow";
import { useResumeUploadWorkflow } from "@/lib/workflows/useResumeUploadWorkflow";

const starterResume = `EXPERIENCE
Backend Engineer, Example Systems
- Built Spring Boot services for resume processing and interview workflows.
- Improved PostgreSQL query performance for high-volume profile search.
- Added Redis-backed rate limiting and operational dashboards.

SKILLS
Java, Spring Boot, PostgreSQL, Redis, Next.js, TypeScript, observability

EDUCATION
B.S. Computer Science`;

const navSections = ["resume", "assessment", "interview"];

export default function Home() {
  const activeSection = useScrollSpy(navSections, "resume");
  const [resumeId, setResumeId] = useState<string | null>(null);
  const [resumeText, setResumeText] = useState(starterResume);
  const [resumeEvidenceSource, setResumeEvidenceSource] = useState<ResumeEvidenceSource | null>(null);
  const [jobDescriptionId, setJobDescriptionId] = useState<string | null>(null);
  const [jobDescription, setJobDescription] = useState("");
  const [targetRole, setTargetRole] = useState("Backend Engineer");
  const [seniority, setSeniority] = useState("Mid-level");
  const [assessment, setAssessment] = useState<Assessment | null>(null);
  const [questions, setQuestions] = useState<InterviewQuestion[]>([]);
  const [activeQuestionId, setActiveQuestionId] = useState<string | null>(null);
  const [answer, setAnswer] = useState("");
  const [answerFeedbackByQuestion, setAnswerFeedbackByQuestion] = useState<Record<string, AnswerFeedback>>({});
  const [displayedAnalysisContext, setDisplayedAnalysisContext] = useState<AiAnalysisPayload | null>(null);
  const [questionSetId, setQuestionSetId] = useState<string | null>(null);
  const resumeTextareaRef = useRef<HTMLTextAreaElement>(null);
  const questionSetIdRef = useRef<string | null>(null);
  const feedbackSubmissionGenerationRef = useRef(0);
  const sourceWorkflowActiveRef = useRef(false);
  const sourceRevisionRef = useRef(0);

  const analysisWorkflow = useAnalysisWorkflow({
    sourceRevisionRef,
    hydrateSource: (context) => {
      setResumeId(context.resumeId ?? null);
      setResumeText(context.resumeText);
      setResumeEvidenceSource(null);
      setJobDescriptionId(context.jobDescriptionId ?? null);
      setJobDescription(context.jobDescription);
      setTargetRole(context.targetRole);
      setSeniority(context.seniority);
    },
    captureInputRefs,
    applyResult: applyAnalysisResult,
    invalidateResults: invalidateAnalysisResults
  });

  const feedbackWorkflow = useFeedbackWorkflow({
    questionCount: questions.length,
    questionSetIdRef,
    restoreContext: restoreFeedbackContext,
    setFeedback: setFeedbackForQuestion,
    setNotice: analysisWorkflow.setNotice,
    captureInputRefs
  });

  const uploadWorkflow = useResumeUploadWorkflow({
    resumeTextareaRef,
    sourceRevisionRef,
    sourceWorkflowActiveRef,
    invalidateAnalysis: invalidateAnalysisResults,
    applyResume: (resume) => {
      setResumeText(resume.normalizedText);
      setResumeEvidenceSource({
        normalizedText: resume.normalizedText,
        chunks: resume.chunks
      });
    },
    applyFallbackText: (text) => {
      setResumeText(text);
      setResumeEvidenceSource(null);
    },
    clearResumeForExtraction: () => {
      setResumeText("");
      setResumeEvidenceSource(null);
    },
    captureResumeId: setResumeId
  });

  sourceWorkflowActiveRef.current = Boolean(
    uploadWorkflow.activeJobId ||
    analysisWorkflow.activeJobId ||
    feedbackWorkflow.activeJobId ||
    uploadWorkflow.isUploadingResume ||
    analysisWorkflow.isAnalyzing ||
    feedbackWorkflow.isSubmittingAnswer
  );

  // On narrow screens the results live far below the input panel, so a finished
  // analysis would otherwise land entirely off-screen.
  const hadAssessmentRef = useRef(false);
  useEffect(() => {
    const arrived = Boolean(assessment) && !hadAssessmentRef.current;
    hadAssessmentRef.current = Boolean(assessment);
    if (!arrived || typeof window === "undefined" || typeof window.matchMedia !== "function") {
      return;
    }
    if (!window.matchMedia("(max-width: 1180px)").matches) {
      return;
    }
    const target = document.getElementById("assessment");
    if (!target || typeof target.scrollIntoView !== "function") {
      return;
    }
    const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    target.scrollIntoView({ behavior: reduceMotion ? "auto" : "smooth", block: "start" });
  }, [assessment]);

  const activeQuestion = useMemo(
    () => questions.find((question) => question.id === activeQuestionId) ?? questions[0],
    [activeQuestionId, questions]
  );
  const answerFeedback = activeQuestion
    ? answerFeedbackByQuestion[activeQuestion.id] ?? null
    : null;
  const assessmentTargetRole = assessment
    ? displayedAnalysisContext?.targetRole ?? "Submitted role"
    : targetRole;
  const assessmentSeniority = assessment
    ? displayedAnalysisContext?.seniority ?? "Submitted level"
    : seniority;

  async function runAssessment() {
    if (uploadWorkflow.isUploadingResume || analysisWorkflow.isAnalyzing) {
      return;
    }
    if (!resumeText.trim()) {
      analysisWorkflow.setNotice("Add resume text or upload a resume before running Gemini analysis.");
      return;
    }
    sourceRevisionRef.current += 1;

    const payload: AiAnalysisPayload = {
      resumeId,
      resumeText,
      jobDescriptionId,
      jobDescription,
      targetRole,
      seniority
    };
    invalidateAnalysisResults();
    analysisWorkflow.setIsAnalyzing(true);
    analysisWorkflow.setNotice(null);

    try {
      const accepted = await createAiAnalysis(payload);
      const refs = accepted.inputRefs ?? emptyInputRefs();
      captureInputRefs(refs.resumeId, refs.jobDescriptionId);
      analysisWorkflow.start(accepted, contextWithRefs(payload, refs));
    }
    catch (error) {
      applyAnalysisResult(
        createAssessment(payload.resumeText, payload.jobDescription),
        createQuestions(payload.resumeText, payload.jobDescription),
        payload,
        createGenerationToken()
      );
      analysisWorkflow.setNotice(
        `Analysis job could not be submitted: ${friendlyError(error)} Local draft results are shown.`
      );
      analysisWorkflow.setIsAnalyzing(false);
    }
  }

  async function submitAnswer() {
    if (!answer.trim() || !activeQuestion || feedbackWorkflow.isSubmittingAnswer) {
      return;
    }
    if (!displayedAnalysisContext || !questionSetId) {
      analysisWorkflow.setNotice(
        "Run the analysis again before requesting feedback so the submitted resume context is available."
      );
      return;
    }

    feedbackWorkflow.setIsSubmittingAnswer(true);
    const submissionGeneration = feedbackSubmissionGenerationRef.current + 1;
    feedbackSubmissionGenerationRef.current = submissionGeneration;
    setAnswerFeedbackByQuestion((current) => {
      const next = { ...current };
      delete next[activeQuestion.id];
      return next;
    });
    const payload: AnswerFeedbackPayload = {
      ...displayedAnalysisContext,
      questionText: activeQuestion.questionText,
      category: activeQuestion.category,
      expectedSignals: activeQuestion.expectedSignals,
      answerText: answer
    };
    const pending: PendingFeedbackInfo = {
      analysis: displayedAnalysisContext,
      questions,
      questionSetId,
      questionId: activeQuestion.id,
      answer,
      payload
    };

    try {
      const accepted = await createAiAnswerFeedback(payload);
      if (
        feedbackSubmissionGenerationRef.current !== submissionGeneration ||
        questionSetIdRef.current !== pending.questionSetId
      ) {
        return;
      }
      const refs = accepted.inputRefs ?? emptyInputRefs();
      captureInputRefs(refs.resumeId, refs.jobDescriptionId);
      const stableAnalysis = contextWithRefs(pending.analysis, refs);
      feedbackWorkflow.start(accepted, {
        ...pending,
        analysis: stableAnalysis,
        payload: { ...pending.payload, ...stableAnalysis }
      });
    }
    catch (error) {
      if (
        feedbackSubmissionGenerationRef.current !== submissionGeneration ||
        questionSetIdRef.current !== pending.questionSetId
      ) {
        return;
      }
      setFeedbackForQuestion(pending.questionId, scoreAnswer(pending.answer, pending.payload.seniority));
      analysisWorkflow.setNotice(
        `Gemini feedback unavailable: ${friendlyError(error)} Local draft feedback is shown.`
      );
      feedbackWorkflow.setIsSubmittingAnswer(false);
    }
  }

  function captureInputRefs(nextResumeId: string | null, nextJobDescriptionId: string | null) {
    if (nextResumeId) {
      setResumeId(nextResumeId);
    }
    setJobDescriptionId(nextJobDescriptionId);
  }

  function applyAnalysisResult(
    nextAssessment: Assessment | null,
    nextQuestions: InterviewQuestion[],
    context: AiAnalysisPayload | null,
    nextQuestionSetId: string
  ) {
    setAssessment(nextAssessment);
    setQuestions(nextQuestions);
    setActiveQuestionId(nextQuestions[0]?.id ?? null);
    setAnswerFeedbackByQuestion({});
    setDisplayedAnalysisContext(context);
    setQuestionSetId(nextQuestionSetId);
    questionSetIdRef.current = nextQuestionSetId;
    setAnswer("");
  }

  function invalidateAnalysisResults() {
    feedbackSubmissionGenerationRef.current += 1;
    if (feedbackWorkflow.activeJobId) {
      feedbackWorkflow.finish();
    }
    feedbackWorkflow.setIsSubmittingAnswer(false);
    setAssessment(null);
    setQuestions([]);
    setActiveQuestionId(null);
    setAnswer("");
    setAnswerFeedbackByQuestion({});
    setDisplayedAnalysisContext(null);
    setQuestionSetId(null);
    questionSetIdRef.current = null;
    analysisWorkflow.setNotice(null);
  }

  function restoreFeedbackContext(pending: PendingFeedbackInfo) {
    const context = pending.analysis;
    setResumeId(context.resumeId ?? null);
    setResumeText(context.resumeText);
    setJobDescriptionId(context.jobDescriptionId ?? null);
    setJobDescription(context.jobDescription);
    setTargetRole(context.targetRole);
    setSeniority(context.seniority);
    setQuestions(pending.questions);
    setActiveQuestionId(pending.questionId);
    setAnswer(pending.answer);
    setDisplayedAnalysisContext(context);
    setQuestionSetId(pending.questionSetId);
    questionSetIdRef.current = pending.questionSetId;
  }

  function setFeedbackForQuestion(questionId: string, feedback: AnswerFeedback) {
    setAnswerFeedbackByQuestion((current) => ({ ...current, [questionId]: feedback }));
  }

  function updateTargetRole(value: string) {
    sourceRevisionRef.current += 1;
    setTargetRole(value);
    invalidateAnalysisResults();
  }

  function updateSeniority(value: string) {
    sourceRevisionRef.current += 1;
    setSeniority(value);
    invalidateAnalysisResults();
  }

  function updateResumeText(value: string) {
    sourceRevisionRef.current += 1;
    setResumeId(null);
    setResumeText(value);
    setResumeEvidenceSource(null);
    invalidateAnalysisResults();
  }

  function updateJobDescription(value: string) {
    sourceRevisionRef.current += 1;
    setJobDescriptionId(null);
    setJobDescription(value);
    invalidateAnalysisResults();
  }

  function updateActiveQuestion(questionId: string) {
    if (feedbackWorkflow.isSubmittingAnswer) {
      return;
    }
    setActiveQuestionId(questionId);
    setAnswer("");
  }

  const evidenceResumeText = displayedAnalysisContext?.resumeText ?? resumeText;
  const evidenceJobDescription = displayedAnalysisContext?.jobDescription ?? jobDescription;
  const evidenceResumeChunks = resumeEvidenceSource?.normalizedText === evidenceResumeText
    ? resumeEvidenceSource.chunks
    : undefined;

  return (
    <main className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">
            <Sparkles size={18} aria-hidden="true" />
          </div>
          <div>
            <strong>AI Interview</strong>
            <span>Tech resume coach</span>
          </div>
        </div>

        <nav className="nav-list" aria-label="Primary">
          <a
            className={`nav-item ${activeSection === "resume" ? "active" : ""}`}
            href="#resume"
            aria-current={activeSection === "resume" ? "true" : undefined}
          >
            <FileText size={18} aria-hidden="true" />
            Resume
          </a>
          <a
            className={`nav-item ${activeSection === "assessment" ? "active" : ""}`}
            href="#assessment"
            aria-current={activeSection === "assessment" ? "true" : undefined}
          >
            <Gauge size={18} aria-hidden="true" />
            Assessment
          </a>
          <a
            className={`nav-item ${activeSection === "interview" ? "active" : ""}`}
            href="#interview"
            aria-current={activeSection === "interview" ? "true" : undefined}
          >
            <MessageSquareText size={18} aria-hidden="true" />
            Interview
          </a>
        </nav>

        <div className="sidebar-status">
          <span className="status-dot" />
          <div>
            <strong>PDF/DOCX + text</strong>
            <span>RAG + Gemini ready</span>
          </div>
        </div>
      </aside>

      <section className="workspace">
        <header className="topbar">
          <div>
            <p className="eyebrow">Candidate workspace</p>
            <h1>Resume assessment and interview practice</h1>
          </div>
          <button
            className="secondary-button"
            type="button"
            onClick={runAssessment}
            disabled={analysisWorkflow.isAnalyzing || uploadWorkflow.isUploadingResume}
          >
            {analysisWorkflow.isAnalyzing
              ? <Loader2 className="spin" size={16} aria-hidden="true" />
              : <RefreshCw size={16} aria-hidden="true" />}
            Refresh analysis
          </button>
        </header>

        <section className="layout-grid">
          <ResumeInputPanel
            uploadedResume={uploadWorkflow.uploadedResume}
            extractionProgress={uploadWorkflow.extractionProgress}
            elapsedSeconds={uploadWorkflow.elapsedSeconds}
            targetRole={targetRole}
            seniority={seniority}
            resumeText={resumeText}
            jobDescription={jobDescription}
            analysisNotice={analysisWorkflow.notice}
            analysisStage={analysisWorkflow.stage}
            analysisElapsedSeconds={analysisWorkflow.elapsedSeconds}
            isAnalyzing={analysisWorkflow.isAnalyzing}
            isUploadingResume={uploadWorkflow.isUploadingResume}
            resumeTextareaRef={resumeTextareaRef}
            onResumeUpload={uploadWorkflow.handleResumeUpload}
            onRecoverLatestResume={uploadWorkflow.recoverLatestResume}
            onTargetRoleChange={updateTargetRole}
            onSeniorityChange={updateSeniority}
            onResumeTextChange={updateResumeText}
            onJobDescriptionChange={updateJobDescription}
            onRunAssessment={runAssessment}
          />

          <div className="right-rail">
            <AssessmentPanel
              targetRole={assessmentTargetRole}
              seniority={assessmentSeniority}
              assessment={assessment}
              isAnalyzing={analysisWorkflow.isAnalyzing}
              evidenceResumeText={evidenceResumeText}
              evidenceJobDescription={evidenceJobDescription}
              evidenceResumeChunks={evidenceResumeChunks}
            />
            <InterviewPracticePanel
              questions={questions}
              activeQuestion={activeQuestion}
              answer={answer}
              answerFeedback={answerFeedback}
              isSubmittingAnswer={feedbackWorkflow.isSubmittingAnswer}
              isAnalyzing={analysisWorkflow.isAnalyzing}
              evidenceResumeText={evidenceResumeText}
              evidenceJobDescription={evidenceJobDescription}
              evidenceResumeChunks={evidenceResumeChunks}
              onActiveQuestionChange={updateActiveQuestion}
              onAnswerChange={setAnswer}
              onSubmitAnswer={submitAnswer}
            />
          </div>
        </section>
      </section>
    </main>
  );
}

function contextWithRefs(context: AiAnalysisPayload, refs: JobInputRefs): AiAnalysisPayload {
  return {
    ...context,
    resumeId: refs.resumeId ?? context.resumeId,
    jobDescriptionId: refs.jobDescriptionId
  };
}

function emptyInputRefs(): JobInputRefs {
  return { resumeId: null, jobDescriptionId: null };
}
