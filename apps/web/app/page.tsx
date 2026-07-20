"use client";

import {
  FileText,
  Gauge,
  Loader2,
  MessageSquareText,
  RefreshCw,
  Sparkles
} from "lucide-react";
import { ChangeEvent, useEffect, useMemo, useRef, useState } from "react";
import { AssessmentPanel } from "@/components/AssessmentPanel";
import { InterviewPracticePanel } from "@/components/InterviewPracticePanel";
import {
  ExtractionProgress,
  ResumeInputPanel,
  UploadedResume
} from "@/components/ResumeInputPanel";
import {
  AiAnalysisPayload,
  AnswerFeedbackPayload,
  createAiAnalysis,
  createAiAnswerFeedback
} from "@/lib/api/ai";
import { isTerminalJob, JobStage } from "@/lib/api/jobs";
import { getCurrentResume, ResumeUploadResponse, uploadResume } from "@/lib/api/resumes";
import type { EvidenceChunk } from "@/lib/evidence";
import { createGenerationToken } from "@/lib/jobWorkflow";
import { useJobPolling } from "@/lib/useJobPolling";
import {
  AnswerFeedback,
  Assessment,
  createAssessment,
  createQuestions,
  InterviewQuestion,
  scoreAnswer
} from "@/lib/mockAssessment";

const starterResume = `EXPERIENCE
Backend Engineer, Example Systems
- Built Spring Boot services for resume processing and interview workflows.
- Improved PostgreSQL query performance for high-volume profile search.
- Added Redis-backed rate limiting and operational dashboards.

SKILLS
Java, Spring Boot, PostgreSQL, Redis, Next.js, TypeScript, observability

EDUCATION
B.S. Computer Science`;

type ResumeFileInfo = {
  name: string;
  size: number;
  extension: string;
};

type PendingFeedbackInfo = {
  analysis: AiAnalysisPayload;
  questions: InterviewQuestion[];
  questionSetId: string;
  questionId: string;
  answer: string;
  payload: AnswerFeedbackPayload;
};

type ResumeJobContext = ResumeFileInfo & {
  startedAt: number;
  textFallback: string | null;
};

type AnalysisJobResult = {
  assessment: Assessment | null;
  questions: {
    questions: InterviewQuestion[];
    modelProvider: string;
  } | null;
};

type ResumeEvidenceSource = {
  normalizedText: string;
  chunks: EvidenceChunk[];
};

export default function Home() {
  const [resumeText, setResumeText] = useState(starterResume);
  const [resumeEvidenceSource, setResumeEvidenceSource] = useState<ResumeEvidenceSource | null>(null);
  const [uploadedResume, setUploadedResume] = useState<UploadedResume | null>(null);
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
  const [analysisNotice, setAnalysisNotice] = useState<string | null>(null);
  const [isAnalyzing, setIsAnalyzing] = useState(false);
  const [isSubmittingAnswer, setIsSubmittingAnswer] = useState(false);
  const [isUploadingResume, setIsUploadingResume] = useState(false);
  const [extractionProgress, setExtractionProgress] = useState<ExtractionProgress | null>(null);
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const resumeTextareaRef = useRef<HTMLTextAreaElement>(null);
  const pendingTextFallbackRef = useRef<string | null>(null);
  const questionSetIdRef = useRef<string | null>(null);
  const hydratedAnalysisGenerationRef = useRef<string | null>(null);
  const hydratedFeedbackGenerationRef = useRef<string | null>(null);
  const feedbackSubmissionGenerationRef = useRef(0);
  const sourceWorkflowActiveRef = useRef(false);
  const sourceRevisionRef = useRef(0);
  const uploadPolling = useJobPolling<ResumeUploadResponse, ResumeJobContext>("ai-interview:job:resume");
  const analysisPolling = useJobPolling<AnalysisJobResult, AiAnalysisPayload>("ai-interview:job:analysis");
  const feedbackPolling = useJobPolling<AnswerFeedback, PendingFeedbackInfo>("ai-interview:job:feedback");

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
  sourceWorkflowActiveRef.current = Boolean(
    uploadPolling.activeJobId ||
    analysisPolling.activeJobId ||
    feedbackPolling.activeJobId ||
    isUploadingResume ||
    isAnalyzing ||
    isSubmittingAnswer
  );

  useEffect(() => {
    let cancelled = false;
    const initialSourceRevision = sourceRevisionRef.current;

    getCurrentResume()
      .then((currentResume) => {
        if (
          cancelled ||
          !currentResume ||
          sourceWorkflowActiveRef.current ||
          sourceRevisionRef.current !== initialSourceRevision
        ) {
          return;
        }

        setResumeText(currentResume.normalizedText);
        setResumeEvidenceSource({
          normalizedText: currentResume.normalizedText,
          chunks: currentResume.chunks
        });
        setUploadedResume({
          name: currentResume.originalFilename,
          size: currentResume.sizeBytes,
          extension: currentResume.originalFilename.split(".").pop()?.toLowerCase() ?? "",
          status: "ready",
          message: "Loaded latest backend extraction"
        });
        setIsUploadingResume(false);
        setExtractionProgress(null);
      })
      .catch(() => {
        // The app can still work with pasted text if no backend resume is available.
      });

    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!isUploadingResume || !extractionProgress) {
      setElapsedSeconds(0);
      return;
    }
    const updateElapsed = () => {
      setElapsedSeconds(Math.max(0, Math.floor((Date.now() - extractionProgress.startedAt) / 1000)));
    };
    updateElapsed();
    const intervalId = window.setInterval(updateElapsed, 1_000);
    return () => window.clearInterval(intervalId);
  }, [extractionProgress, isUploadingResume]);

  useEffect(() => {
    if (uploadPolling.terminalError) {
      const pending = uploadPolling.context;
      setIsUploadingResume(false);
      setExtractionProgress(null);
      if (pending?.textFallback) {
        setResumeText(pending.textFallback);
        setResumeEvidenceSource(null);
        setUploadedResume({
          name: pending.name,
          size: pending.size,
          extension: pending.extension,
          status: "ready",
          message: "Job status expired; text loaded from this tab"
        });
      }
      else {
        setUploadedResume((current) => {
          const previous = current ?? {
            name: pending?.name ?? "Resume upload",
            size: pending?.size ?? 0,
            extension: pending?.extension ?? "",
            status: "error" as const,
            message: ""
          };
          return {
            ...previous,
            status: "error",
            message: extractionErrorMessage(uploadPolling.terminalError)
          };
        });
      }
      uploadPolling.finish();
      return;
    }

    if (!uploadPolling.activeJobId) {
      return;
    }
    const job = uploadPolling.job;
    if (!job) {
      setIsUploadingResume(true);
      setExtractionProgress((current) => current ?? {
        startedAt: uploadPolling.context?.startedAt ?? Date.now(),
        stage: uploadPolling.connectionError ? "Waiting to reconnect to the job service" : "Waiting for the worker"
      });
      setUploadedResume((current) => current ?? {
        name: uploadPolling.context?.name ?? "Resume upload",
        size: uploadPolling.context?.size ?? 0,
        extension: uploadPolling.context?.extension ?? "",
        status: "extracting",
        message: "Waiting for the worker"
      });
      return;
    }

    if (!isTerminalJob(job.status)) {
      const stage = uploadPolling.connectionError
        ? "Connection interrupted; the backend job is still active"
        : jobStageLabel(job.stage);
      setIsUploadingResume(true);
      setExtractionProgress((current) => ({
        startedAt: current?.startedAt ?? Date.parse(job.createdAt),
        stage
      }));
      setUploadedResume((current) => current ? { ...current, status: "extracting", message: stage } : current);
      return;
    }

    setIsUploadingResume(false);
    setExtractionProgress(null);
    if ((job.status === "SUCCEEDED" || job.status === "PARTIAL") && job.result) {
      applyExtractedResume(job.result, fileExtension(job.result.originalFilename));
      pendingTextFallbackRef.current = null;
      uploadPolling.finish();
      return;
    }

    const textFallback = uploadPolling.context?.textFallback ?? pendingTextFallbackRef.current;
    if (textFallback) {
      setResumeText(textFallback);
      setResumeEvidenceSource(null);
      setUploadedResume((current) => current ? {
        ...current,
        status: "ready",
        message: "Backend extraction failed; text loaded in the browser"
      } : current);
      pendingTextFallbackRef.current = null;
      uploadPolling.finish();
      return;
    }
    setUploadedResume((current) => current ? {
      ...current,
      status: "error",
      message: extractionErrorMessage(job.error?.message ?? "Resume extraction failed")
    } : current);
    uploadPolling.finish();
  }, [
    uploadPolling.activeJobId,
    uploadPolling.connectionError,
    uploadPolling.context,
    uploadPolling.finish,
    uploadPolling.job,
    uploadPolling.terminalError
  ]);

  useEffect(() => {
    const snapshot = analysisPolling.context;
    if (
      snapshot &&
      analysisPolling.restored &&
      analysisPolling.generation !== hydratedAnalysisGenerationRef.current
    ) {
      sourceRevisionRef.current += 1;
      setResumeText(snapshot.resumeText);
      setJobDescription(snapshot.jobDescription);
      setTargetRole(snapshot.targetRole);
      setSeniority(snapshot.seniority);
      hydratedAnalysisGenerationRef.current = analysisPolling.generation;
    }

    if (analysisPolling.terminalError) {
      setIsAnalyzing(false);
      if (snapshot) {
        applyAnalysisResult(
          createAssessment(snapshot.resumeText, snapshot.jobDescription),
          createQuestions(snapshot.resumeText, snapshot.jobDescription),
          snapshot,
          analysisPolling.generation ?? analysisPolling.activeJobId ?? "local-analysis"
        );
        setAnalysisNotice(
          `Analysis job could not be recovered: ${friendlyAiError(analysisPolling.terminalError)} Local draft results are shown.`
        );
      }
      else {
        invalidateAnalysisResults();
        setAnalysisNotice(
          `Analysis job could not be recovered: ${friendlyAiError(analysisPolling.terminalError)} Run the analysis again.`
        );
      }
      analysisPolling.finish();
      return;
    }

    if (!analysisPolling.activeJobId) {
      return;
    }
    const job = analysisPolling.job;
    if (!job || !isTerminalJob(job.status)) {
      setIsAnalyzing(true);
      setAnalysisNotice(analysisPolling.connectionError
        ? "Connection interrupted. The analysis job is still running and polling will continue."
        : job ? jobStageLabel(job.stage) : "Waiting for the analysis worker");
      return;
    }

    setIsAnalyzing(false);
    const generation = analysisPolling.generation ?? job.jobId;
    const result = job.result;
    const aiQuestions = result?.questions?.questions ?? [];
    const fallbackQuestions = createQuestions(
      snapshot?.resumeText ?? "",
      snapshot?.jobDescription ?? ""
    );
    if (job.status === "SUCCEEDED" && result?.assessment) {
      applyAnalysisResult(
        result.assessment,
        aiQuestions.length > 0 ? aiQuestions : fallbackQuestions,
        snapshot,
        generation
      );
      setAnalysisNotice(
        aiQuestions.length > 0
          ? null
          : "Gemini returned no usable questions; local draft questions are shown."
      );
      analysisPolling.finish();
      return;
    }

    if (job.status === "PARTIAL") {
      const nextAssessment = result?.assessment ?? (
        snapshot ? createAssessment(snapshot.resumeText, snapshot.jobDescription) : null
      );
      applyAnalysisResult(
        nextAssessment,
        aiQuestions.length > 0 ? aiQuestions : fallbackQuestions,
        snapshot,
        generation
      );
      const assessmentMissing = !result?.assessment;
      const questionsMissing = aiQuestions.length === 0;
      if (assessmentMissing && !snapshot) {
        setAnalysisNotice("Gemini assessment was unavailable and the original input snapshot could not be restored.");
      }
      else if (assessmentMissing && questionsMissing) {
        setAnalysisNotice("Gemini returned a partial result; local draft assessment and questions fill the missing output.");
      }
      else if (assessmentMissing) {
        setAnalysisNotice("Gemini assessment was unavailable; a local draft assessment fills the missing result.");
      }
      else if (questionsMissing) {
        setAnalysisNotice("Gemini question generation was unavailable; local draft questions fill the missing result.");
      }
      else {
        setAnalysisNotice("Gemini returned a partial analysis result.");
      }
      analysisPolling.finish();
      return;
    }

    if (snapshot) {
      applyAnalysisResult(
        createAssessment(snapshot.resumeText, snapshot.jobDescription),
        createQuestions(snapshot.resumeText, snapshot.jobDescription),
        snapshot,
        generation
      );
      setAnalysisNotice(`Gemini analysis unavailable: ${friendlyAiError(job.error?.message)} Local draft results are shown.`);
    }
    else {
      invalidateAnalysisResults();
      setAnalysisNotice(
        `Gemini analysis unavailable: ${friendlyAiError(job.error?.message)} The original input snapshot is unavailable; run the analysis again.`
      );
    }
    analysisPolling.finish();
  }, [
    analysisPolling.activeJobId,
    analysisPolling.connectionError,
    analysisPolling.context,
    analysisPolling.finish,
    analysisPolling.generation,
    analysisPolling.job,
    analysisPolling.restored,
    analysisPolling.terminalError
  ]);

  useEffect(() => {
    const pending = feedbackPolling.context;
    if (
      pending &&
      feedbackPolling.restored &&
      feedbackPolling.generation !== hydratedFeedbackGenerationRef.current &&
      !questionSetIdRef.current &&
      questions.length === 0
    ) {
      restoreFeedbackContext(pending);
      hydratedFeedbackGenerationRef.current = feedbackPolling.generation;
    }

    if (feedbackPolling.terminalError) {
      setIsSubmittingAnswer(false);
      if (pending && feedbackContextMatches(pending)) {
        setFeedbackForQuestion(pending.questionId, scoreAnswer(pending.answer));
        setAnalysisNotice(
          `Feedback job could not be recovered: ${friendlyAiError(feedbackPolling.terminalError)} Local draft feedback is shown.`
        );
      }
      else {
        setAnalysisNotice("Feedback job could not be recovered because its submitted question context is unavailable.");
      }
      feedbackPolling.finish();
      return;
    }

    if (!feedbackPolling.activeJobId) {
      return;
    }
    const job = feedbackPolling.job;
    if (!job || !isTerminalJob(job.status)) {
      setIsSubmittingAnswer(true);
      setAnalysisNotice(feedbackPolling.connectionError
        ? "Connection interrupted. The feedback job is still running and polling will continue."
        : job ? jobStageLabel(job.stage) : "Waiting for the feedback worker");
      return;
    }

    setIsSubmittingAnswer(false);
    if (!pending || !feedbackContextMatches(pending)) {
      setAnalysisNotice("Feedback completed for an earlier question set and was ignored.");
      feedbackPolling.finish();
      return;
    }
    if ((job.status === "SUCCEEDED" || job.status === "PARTIAL") && job.result) {
      setFeedbackForQuestion(pending.questionId, job.result);
      setAnalysisNotice(job.status === "PARTIAL" ? "Gemini returned partial feedback." : null);
      feedbackPolling.finish();
      return;
    }
    setFeedbackForQuestion(pending.questionId, scoreAnswer(pending.answer));
    setAnalysisNotice(`Gemini feedback unavailable: ${friendlyAiError(job.error?.message)} Local draft feedback is shown.`);
    feedbackPolling.finish();
  }, [
    feedbackPolling.activeJobId,
    feedbackPolling.connectionError,
    feedbackPolling.context,
    feedbackPolling.finish,
    feedbackPolling.generation,
    feedbackPolling.job,
    feedbackPolling.restored,
    feedbackPolling.terminalError,
    questions.length
  ]);

  async function handleResumeUpload(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) {
      return;
    }
    sourceRevisionRef.current += 1;

    const extension = file.name.split(".").pop()?.toLowerCase() ?? "";
    const isTextFile =
      file.type.startsWith("text/") || ["txt", "text", "md", "markdown"].includes(extension);

    pendingTextFallbackRef.current = isTextFile ? await readTextFile(file).catch(() => null) : null;
    const uploadContext: ResumeJobContext = {
      name: file.name,
      size: file.size,
      extension,
      startedAt: Date.now(),
      textFallback: pendingTextFallbackRef.current
    };

    setUploadedResume({
      name: file.name,
      size: file.size,
      extension,
      status: "extracting",
      message: isTextFile ? "Extracting text" : "Extracting PDF/DOCX text on the backend"
    });
    invalidateAnalysisResults();
    setIsUploadingResume(true);
    setExtractionProgress({
      startedAt: uploadContext.startedAt,
      stage: "Uploading file to object storage"
    });
    if (!isTextFile) {
      setResumeText("");
    }
    setResumeEvidenceSource(null);

    try {
      const accepted = await uploadResume(file);
      uploadPolling.start(accepted, uploadContext);
    }
    catch (error) {
      if (pendingTextFallbackRef.current) {
        setResumeText(pendingTextFallbackRef.current);
        setResumeEvidenceSource(null);
        setUploadedResume({
          name: file.name,
          size: file.size,
          extension,
          status: "ready",
          message: "Backend unavailable; text loaded in the browser"
        });
      }
      else {
        setUploadedResume({
          name: file.name,
          size: file.size,
          extension,
          status: "error",
          message: extractionErrorMessage(error)
        });
      }
      setIsUploadingResume(false);
      setExtractionProgress(null);
    }
    event.target.value = "";
  }

  function applyExtractedResume(
    response: ResumeUploadResponse,
    extension: string,
    message?: string
  ) {
    setResumeText(response.normalizedText);
    setResumeEvidenceSource({
      normalizedText: response.normalizedText,
      chunks: response.chunks
    });
    setUploadedResume({
      name: response.originalFilename,
      size: response.sizeBytes,
      extension,
      status: "ready",
      message: message ?? `Extracted ${response.normalizedTextLength.toLocaleString()} characters and inserted them below`
    });
    window.requestAnimationFrame(() => {
      resumeTextareaRef.current?.focus();
    });
  }

  async function recoverLatestResumeFromBackend() {
    if (!uploadedResume) {
      return;
    }

    try {
      const currentResume = await getCurrentResume();
      if (
        currentResume &&
        currentResume.originalFilename === uploadedResume.name &&
        currentResume.sizeBytes === uploadedResume.size
      ) {
        applyExtractedResume(
          currentResume,
          uploadedResume.extension,
          "Backend completed extraction; inserted the latest text below"
        );
        return;
      }

      setUploadedResume({
        ...uploadedResume,
        message: "No matching backend extraction is available yet. Try uploading again."
      });
    }
    catch {
      setUploadedResume({
        ...uploadedResume,
        message: "Backend extractor is not reachable. Start the Spring Boot API and try again."
      });
    }
  }

  async function runAssessment() {
    if (isUploadingResume || isAnalyzing) {
      return;
    }
    if (!resumeText.trim()) {
      setAnalysisNotice("Add resume text or upload a resume before running Gemini analysis.");
      return;
    }
    sourceRevisionRef.current += 1;

    const payload: AiAnalysisPayload = {
      resumeText,
      jobDescription,
      targetRole,
      seniority
    };
    invalidateAnalysisResults();
    setIsAnalyzing(true);
    setAnalysisNotice(null);

    try {
      const accepted = await createAiAnalysis(payload);
      analysisPolling.start(accepted, payload);
    }
    catch (error) {
      applyAnalysisResult(
        createAssessment(payload.resumeText, payload.jobDescription),
        createQuestions(payload.resumeText, payload.jobDescription),
        payload,
        createGenerationToken()
      );
      setAnalysisNotice(`Analysis job could not be submitted: ${friendlyAiError(error)} Local draft results are shown.`);
      setIsAnalyzing(false);
    }
  }

  async function submitAnswer() {
    if (!answer.trim() || !activeQuestion || isSubmittingAnswer) {
      return;
    }
    if (!displayedAnalysisContext || !questionSetId) {
      setAnalysisNotice("Run the analysis again before requesting feedback so the submitted resume context is available.");
      return;
    }

    setIsSubmittingAnswer(true);
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
      feedbackPolling.start(accepted, pending);
    }
    catch (error) {
      if (
        feedbackSubmissionGenerationRef.current !== submissionGeneration ||
        questionSetIdRef.current !== pending.questionSetId
      ) {
        return;
      }
      setFeedbackForQuestion(pending.questionId, scoreAnswer(pending.answer));
      setAnalysisNotice(`Gemini feedback unavailable: ${friendlyAiError(error)} Local draft feedback is shown.`);
      setIsSubmittingAnswer(false);
    }
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
    if (feedbackPolling.activeJobId) {
      feedbackPolling.finish();
    }
    setIsSubmittingAnswer(false);
    setAssessment(null);
    setQuestions([]);
    setActiveQuestionId(null);
    setAnswer("");
    setAnswerFeedbackByQuestion({});
    setDisplayedAnalysisContext(null);
    setQuestionSetId(null);
    questionSetIdRef.current = null;
    setAnalysisNotice(null);
  }

  function restoreFeedbackContext(pending: PendingFeedbackInfo) {
    setQuestions(pending.questions);
    setActiveQuestionId(pending.questionId);
    setAnswer(pending.answer);
    setDisplayedAnalysisContext(pending.analysis);
    setQuestionSetId(pending.questionSetId);
    questionSetIdRef.current = pending.questionSetId;
  }

  function feedbackContextMatches(pending: PendingFeedbackInfo) {
    return questionSetIdRef.current === pending.questionSetId;
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
    setResumeText(value);
    setResumeEvidenceSource(null);
    invalidateAnalysisResults();
  }

  function updateJobDescription(value: string) {
    sourceRevisionRef.current += 1;
    setJobDescription(value);
    invalidateAnalysisResults();
  }

  function updateActiveQuestion(questionId: string) {
    if (isSubmittingAnswer) {
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
          <a className="nav-item active" href="#resume">
            <FileText size={18} aria-hidden="true" />
            Resume
          </a>
          <a className="nav-item" href="#assessment">
            <Gauge size={18} aria-hidden="true" />
            Assessment
          </a>
          <a className="nav-item" href="#interview">
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
          <button className="secondary-button" type="button" onClick={runAssessment} disabled={isAnalyzing || isUploadingResume}>
            {isAnalyzing ? <Loader2 className="spin" size={16} aria-hidden="true" /> : <RefreshCw size={16} aria-hidden="true" />}
            Refresh analysis
          </button>
        </header>

        <section className="layout-grid" id="resume">
          <ResumeInputPanel
            uploadedResume={uploadedResume}
            extractionProgress={extractionProgress}
            elapsedSeconds={elapsedSeconds}
            targetRole={targetRole}
            seniority={seniority}
            resumeText={resumeText}
            jobDescription={jobDescription}
            analysisNotice={analysisNotice}
            isAnalyzing={isAnalyzing}
            isUploadingResume={isUploadingResume}
            resumeTextareaRef={resumeTextareaRef}
            onResumeUpload={handleResumeUpload}
            onRecoverLatestResume={recoverLatestResumeFromBackend}
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
              evidenceResumeText={evidenceResumeText}
              evidenceJobDescription={evidenceJobDescription}
              evidenceResumeChunks={evidenceResumeChunks}
            />
            <InterviewPracticePanel
              questions={questions}
              activeQuestion={activeQuestion}
              answer={answer}
              answerFeedback={answerFeedback}
              isSubmittingAnswer={isSubmittingAnswer}
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

function readTextFile(file: File) {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result ?? ""));
    reader.onerror = () => reject(reader.error ?? new Error("Could not read text file"));
    reader.readAsText(file);
  });
}

function fileExtension(filename: string) {
  return filename.split(".").pop()?.toLowerCase() ?? "";
}

function jobStageLabel(stage: JobStage) {
  const labels: Record<JobStage, string> = {
    QUEUED: "Queued for a worker",
    READING_FILE: "Reading the resume from object storage",
    EXTRACTING_TEXT: "Extracting document text",
    NORMALIZING_TEXT: "Normalizing sections and whitespace",
    CHUNKING_TEXT: "Building resume sections for retrieval",
    ASSESSING_RESUME: "Scoring the resume with Gemini",
    GENERATING_QUESTIONS: "Generating interview questions",
    SCORING_ANSWER: "Evaluating the interview answer",
    COMPLETED: "Completed"
  };
  return labels[stage];
}

function extractionErrorMessage(error: unknown) {
  const message = error instanceof Error
    ? error.message
    : typeof error === "string" ? error : "Could not extract text from this resume";
  if (/No readable resume text/i.test(message)) {
    return "No selectable text was found. This PDF may be scanned; use an OCR PDF or paste the text.";
  }
  if (/timed out/i.test(message)) {
    return "Extraction timed out. This PDF may be scanned, encrypted, or malformed; try an OCR PDF or paste the text.";
  }
  if (/Failed to fetch|NetworkError|Load failed/i.test(message)) {
    return "Backend extractor is not reachable. Start the Spring Boot API on port 8080 and try again.";
  }
  return message;
}

function friendlyAiError(error: unknown) {
  const message = error instanceof Error
    ? error.message
    : typeof error === "string" ? error : "AI request failed";
  if (/Failed to fetch|NetworkError|Load failed/i.test(message)) {
    return "Spring Boot API is not reachable.";
  }
  if (/GEMINI_API_KEY/i.test(message)) {
    return "Gemini key is not configured.";
  }
  if (/quota|rate[- ]?limit/i.test(message)) {
    return "Gemini quota is exhausted for the configured key/model.";
  }
  if (/timed out/i.test(message)) {
    return "the AI request timed out.";
  }
  return message;
}
