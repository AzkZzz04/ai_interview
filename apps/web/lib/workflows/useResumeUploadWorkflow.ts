"use client";

import { ChangeEvent, MutableRefObject, RefObject, useEffect, useRef, useState } from "react";
import type { ExtractionProgress, UploadedResume } from "@/components/ResumeInputPanel";
import { friendlyError } from "@/lib/errorMessages";
import { isTerminalJob, JobStage } from "@/lib/api/jobs";
import { getCurrentResume, ResumeUploadResponse, uploadResume } from "@/lib/api/resumes";
import { useJobPolling } from "@/lib/useJobPolling";
import type { ResumeJobContext } from "@/lib/workflows/types";
import { useLatest } from "@/lib/workflows/useLatest";

type Options = {
  resumeTextareaRef: RefObject<HTMLTextAreaElement>;
  sourceRevisionRef: MutableRefObject<number>;
  sourceWorkflowActiveRef: MutableRefObject<boolean>;
  invalidateAnalysis: () => void;
  applyResume: (response: ResumeUploadResponse) => void;
  applyFallbackText: (text: string) => void;
  clearResumeForExtraction: () => void;
  captureResumeId: (resumeId: string | null) => void;
};

export function useResumeUploadWorkflow(options: Options) {
  const optionsRef = useLatest(options);
  const polling = useJobPolling<ResumeUploadResponse, ResumeJobContext>("ai-interview:job:resume");
  const [uploadedResume, setUploadedResume] = useState<UploadedResume | null>(null);
  const [isUploadingResume, setIsUploadingResume] = useState(false);
  const [extractionProgress, setExtractionProgress] = useState<ExtractionProgress | null>(null);
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const pendingTextFallbackRef = useRef<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    const initialSourceRevision = optionsRef.current.sourceRevisionRef.current;
    const restoringJob = hasPersistedWorkflow();

    getCurrentResume()
      .then((resume) => {
        const current = optionsRef.current;
        if (
          cancelled ||
          !resume ||
          restoringJob ||
          current.sourceWorkflowActiveRef.current ||
          current.sourceRevisionRef.current !== initialSourceRevision
        ) {
          return;
        }
        current.captureResumeId(resume.id);
        current.applyResume(resume);
        setUploadedResume(readyResume(resume, "Loaded latest backend extraction"));
        setIsUploadingResume(false);
        setExtractionProgress(null);
      })
      .catch(() => {
        // Pasted text remains usable when no backend resume exists.
      });

    return () => {
      cancelled = true;
    };
  }, [optionsRef]);

  useEffect(() => {
    if (!isUploadingResume || !extractionProgress) {
      setElapsedSeconds(0);
      return;
    }
    const update = () => setElapsedSeconds(
      Math.max(0, Math.floor((Date.now() - extractionProgress.startedAt) / 1000))
    );
    update();
    const intervalId = window.setInterval(update, 1_000);
    return () => window.clearInterval(intervalId);
  }, [extractionProgress, isUploadingResume]);

  useEffect(() => {
    const current = optionsRef.current;
    const refs = polling.job?.inputRefs;
    if (refs?.resumeId) {
      current.captureResumeId(refs.resumeId);
    }

    if (polling.terminalError) {
      const pending = polling.context;
      setIsUploadingResume(false);
      setExtractionProgress(null);
      if (pending?.textFallback) {
        current.captureResumeId(null);
        current.applyFallbackText(pending.textFallback);
        setUploadedResume({
          name: pending.name,
          size: pending.size,
          extension: pending.extension,
          status: "ready",
          message: "Job status expired; text loaded from this tab"
        });
      }
      else {
        setUploadedResume((value) => ({
          ...(value ?? fallbackResume(pending)),
          status: "error",
          message: extractionErrorMessage(polling.terminalError)
        }));
      }
      polling.finish();
      return;
    }

    if (!polling.activeJobId) {
      return;
    }
    const job = polling.job;
    if (!job) {
      setIsUploadingResume(true);
      setExtractionProgress((value) => value ?? {
        startedAt: polling.context?.startedAt ?? Date.now(),
        stage: polling.connectionError ? "Waiting to reconnect to the job service" : "Waiting for the worker"
      });
      setUploadedResume((value) => value ?? {
        ...fallbackResume(polling.context),
        status: "extracting",
        message: "Waiting for the worker"
      });
      return;
    }

    if (!isTerminalJob(job.status)) {
      const stage = polling.connectionError
        ? "Connection interrupted; the backend job is still active"
        : jobStageLabel(job.stage);
      setIsUploadingResume(true);
      setExtractionProgress((value) => ({
        startedAt: value?.startedAt ?? Date.parse(job.createdAt),
        stage
      }));
      setUploadedResume((value) => value ? { ...value, status: "extracting", message: stage } : value);
      return;
    }

    setIsUploadingResume(false);
    setExtractionProgress(null);
    if ((job.status === "SUCCEEDED" || job.status === "PARTIAL") && job.result) {
      current.captureResumeId(job.inputRefs?.resumeId ?? job.result.id);
      current.applyResume(job.result);
      setUploadedResume(readyResume(
        job.result,
        `Extracted ${job.result.normalizedTextLength.toLocaleString()} characters and inserted them below`
      ));
      pendingTextFallbackRef.current = null;
      window.requestAnimationFrame(() => current.resumeTextareaRef.current?.focus());
      polling.finish();
      return;
    }

    const fallback = polling.context?.textFallback ?? pendingTextFallbackRef.current;
    if (fallback) {
      current.captureResumeId(null);
      current.applyFallbackText(fallback);
      setUploadedResume((value) => value ? {
        ...value,
        status: "ready",
        message: "Backend extraction failed; text loaded in the browser"
      } : value);
      pendingTextFallbackRef.current = null;
      polling.finish();
      return;
    }
    setUploadedResume((value) => value ? {
      ...value,
      status: "error",
      message: extractionErrorMessage(job.error)
    } : value);
    polling.finish();
  }, [optionsRef, polling.activeJobId, polling.connectionError, polling.context, polling.finish, polling.job, polling.terminalError]);

  async function handleResumeUpload(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) {
      return;
    }
    const current = optionsRef.current;
    current.sourceRevisionRef.current += 1;
    current.captureResumeId(null);

    const extension = file.name.split(".").pop()?.toLowerCase() ?? "";
    const isTextFile = file.type.startsWith("text/") || ["txt", "text", "md", "markdown"].includes(extension);
    pendingTextFallbackRef.current = isTextFile ? await readTextFile(file).catch(() => null) : null;
    const context: ResumeJobContext = {
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
    current.invalidateAnalysis();
    setIsUploadingResume(true);
    setExtractionProgress({ startedAt: context.startedAt, stage: "Uploading file to object storage" });
    if (!isTextFile) {
      current.clearResumeForExtraction();
    }

    try {
      const accepted = await uploadResume(file);
      current.captureResumeId(accepted.inputRefs?.resumeId ?? null);
      polling.start(accepted, context);
    }
    catch (error) {
      if (pendingTextFallbackRef.current) {
        current.captureResumeId(null);
        current.applyFallbackText(pendingTextFallbackRef.current);
        setUploadedResume({ ...context, status: "ready", message: "Backend unavailable; text loaded in the browser" });
      }
      else {
        setUploadedResume({ ...context, status: "error", message: extractionErrorMessage(error) });
      }
      setIsUploadingResume(false);
      setExtractionProgress(null);
    }
    event.target.value = "";
  }

  async function recoverLatestResume() {
    if (!uploadedResume) {
      return;
    }
    try {
      const resume = await getCurrentResume();
      if (resume && resume.originalFilename === uploadedResume.name && resume.sizeBytes === uploadedResume.size) {
        optionsRef.current.captureResumeId(resume.id);
        optionsRef.current.applyResume(resume);
        setUploadedResume(readyResume(resume, "Backend completed extraction; inserted the latest text below"));
        return;
      }
      setUploadedResume({ ...uploadedResume, message: "No matching backend extraction is available yet. Try uploading again." });
    }
    catch (error) {
      setUploadedResume({ ...uploadedResume, message: friendlyError(error, "Backend extractor is not reachable.") });
    }
  }

  return {
    uploadedResume,
    isUploadingResume,
    extractionProgress,
    elapsedSeconds,
    activeJobId: polling.activeJobId,
    handleResumeUpload,
    recoverLatestResume
  };
}

function hasPersistedWorkflow() {
  return [
    "ai-interview:job:resume",
    "ai-interview:job:analysis",
    "ai-interview:job:feedback"
  ].some((key) => window.localStorage.getItem(key) !== null);
}

function readyResume(resume: ResumeUploadResponse, message: string): UploadedResume {
  return {
    name: resume.originalFilename,
    size: resume.sizeBytes,
    extension: resume.originalFilename.split(".").pop()?.toLowerCase() ?? "",
    status: "ready",
    message
  };
}

function fallbackResume(context: ResumeJobContext | null | undefined) {
  return {
    name: context?.name ?? "Resume upload",
    size: context?.size ?? 0,
    extension: context?.extension ?? "",
    status: "error" as const,
    message: ""
  };
}

function readTextFile(file: File) {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result ?? ""));
    reader.onerror = () => reject(reader.error ?? new Error("Could not read text file"));
    reader.readAsText(file);
  });
}

function extractionErrorMessage(error: unknown) {
  return friendlyError(error, "Could not extract text from this resume");
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
