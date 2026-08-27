"use client";

import { MutableRefObject, useEffect, useRef, useState } from "react";
import type { AiAnalysisPayload } from "@/lib/api/ai";
import { friendlyError } from "@/lib/errorMessages";
import { isTerminalJob, JobStage } from "@/lib/api/jobs";
import { useJobPolling } from "@/lib/useJobPolling";
import { createAssessment, createQuestions, Assessment, InterviewQuestion } from "@/lib/mockAssessment";
import type { AnalysisJobResult } from "@/lib/workflows/types";
import { useLatest } from "@/lib/workflows/useLatest";

type Options = {
  sourceRevisionRef: MutableRefObject<number>;
  hydrateSource: (context: AiAnalysisPayload) => void;
  captureInputRefs: (resumeId: string | null, jobDescriptionId: string | null) => void;
  applyResult: (
    assessment: Assessment | null,
    questions: InterviewQuestion[],
    context: AiAnalysisPayload | null,
    questionSetId: string
  ) => void;
  invalidateResults: () => void;
};

export function useAnalysisWorkflow(options: Options) {
  const optionsRef = useLatest(options);
  const polling = useJobPolling<AnalysisJobResult, AiAnalysisPayload>("ai-interview:job:analysis");
  const [isAnalyzing, setIsAnalyzing] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [stage, setStage] = useState<string | null>(null);
  const [startedAt, setStartedAt] = useState<number | null>(null);
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const hydratedGenerationRef = useRef<string | null>(null);

  useEffect(() => {
    if (startedAt === null) {
      setElapsedSeconds(0);
      return;
    }
    const update = () => setElapsedSeconds(Math.max(0, Math.floor((Date.now() - startedAt) / 1000)));
    update();
    const intervalId = window.setInterval(update, 1_000);
    return () => window.clearInterval(intervalId);
  }, [startedAt]);

  useEffect(() => {
    const current = optionsRef.current;
    const snapshot = polling.context;
    if (
      snapshot &&
      polling.restored &&
      polling.generation !== hydratedGenerationRef.current
    ) {
      current.sourceRevisionRef.current += 1;
      current.hydrateSource(snapshot);
      hydratedGenerationRef.current = polling.generation;
    }

	    const refs = polling.job?.inputRefs;
	    if (refs) {
	      current.captureInputRefs(refs.resumeId, refs.jobDescriptionId);
	    }
	    const resolvedSnapshot = snapshot && refs
	      ? {
	          ...snapshot,
	          resumeId: refs.resumeId,
	          jobDescriptionId: refs.jobDescriptionId
	        }
	      : snapshot;

	    if (polling.terminalError) {
	      setIsAnalyzing(false);
	      setStage(null);
	      setStartedAt(null);
	      if (resolvedSnapshot) {
	        current.applyResult(
	          createAssessment(resolvedSnapshot.resumeText, resolvedSnapshot.jobDescription, resolvedSnapshot.seniority),
	          createQuestions(resolvedSnapshot.resumeText, resolvedSnapshot.jobDescription, resolvedSnapshot.seniority),
	          resolvedSnapshot,
          polling.generation ?? polling.activeJobId ?? "local-analysis"
        );
        setNotice(`Analysis job could not be recovered: ${friendlyError(polling.terminalError)} Local draft results are shown.`);
      }
      else {
        current.invalidateResults();
        setNotice(`Analysis job could not be recovered: ${friendlyError(polling.terminalError)} Run the analysis again.`);
      }
      polling.finish();
      return;
    }

    if (!polling.activeJobId) {
      return;
    }
    const job = polling.job;
    if (!job || !isTerminalJob(job.status)) {
      setIsAnalyzing(true);
      setStartedAt((value) => value ?? (job ? Date.parse(job.createdAt) : Date.now()));
      setStage(job ? jobStageLabel(job.stage) : "Waiting for the analysis worker");
      setNotice(polling.connectionError
        ? "Connection interrupted. The analysis job is still running and polling will continue."
        : null);
      return;
    }

    setIsAnalyzing(false);
    setStage(null);
    setStartedAt(null);
    const generation = polling.generation ?? job.jobId;
    const result = job.result;
    const aiQuestions = result?.questions?.questions ?? [];
	    const fallbackQuestions = createQuestions(
	      resolvedSnapshot?.resumeText ?? "",
	      resolvedSnapshot?.jobDescription ?? "",
	      resolvedSnapshot?.seniority
	    );
    if (job.status === "SUCCEEDED" && result?.assessment) {
      current.applyResult(
        result.assessment,
        aiQuestions.length > 0 ? aiQuestions : fallbackQuestions,
	        resolvedSnapshot,
        generation
      );
      setNotice(aiQuestions.length > 0 ? null : "Gemini returned no usable questions; local draft questions are shown.");
      polling.finish();
      return;
    }

    if (job.status === "PARTIAL") {
      const nextAssessment = result?.assessment ?? (
	        resolvedSnapshot
	          ? createAssessment(resolvedSnapshot.resumeText, resolvedSnapshot.jobDescription, resolvedSnapshot.seniority)
	          : null
      );
      current.applyResult(
        nextAssessment,
        aiQuestions.length > 0 ? aiQuestions : fallbackQuestions,
	        resolvedSnapshot,
        generation
      );
      const assessmentMissing = !result?.assessment;
      const questionsMissing = aiQuestions.length === 0;
	      setNotice(partialNotice(assessmentMissing, questionsMissing, Boolean(resolvedSnapshot)));
      polling.finish();
      return;
    }

	    if (resolvedSnapshot) {
	      current.applyResult(
	        createAssessment(resolvedSnapshot.resumeText, resolvedSnapshot.jobDescription, resolvedSnapshot.seniority),
	        createQuestions(resolvedSnapshot.resumeText, resolvedSnapshot.jobDescription, resolvedSnapshot.seniority),
	        resolvedSnapshot,
        generation
      );
      setNotice(`Gemini analysis unavailable: ${friendlyError(job.error)} Local draft results are shown.`);
    }
    else {
      current.invalidateResults();
      setNotice(`Gemini analysis unavailable: ${friendlyError(job.error)} The original input snapshot is unavailable; run the analysis again.`);
    }
    polling.finish();
  }, [optionsRef, polling.activeJobId, polling.connectionError, polling.context, polling.finish, polling.generation, polling.job, polling.restored, polling.terminalError]);

  return {
    ...polling,
    isAnalyzing,
    setIsAnalyzing,
    notice,
    setNotice,
    stage,
    elapsedSeconds
  };
}

function partialNotice(assessmentMissing: boolean, questionsMissing: boolean, hasSnapshot: boolean) {
  if (assessmentMissing && !hasSnapshot) {
    return "Gemini assessment was unavailable and the original input snapshot could not be restored.";
  }
  if (assessmentMissing && questionsMissing) {
    return "Gemini returned a partial result; local draft assessment and questions fill the missing output.";
  }
  if (assessmentMissing) {
    return "Gemini assessment was unavailable; a local draft assessment fills the missing result.";
  }
  if (questionsMissing) {
    return "Gemini question generation was unavailable; local draft questions fill the missing result.";
  }
  return "Gemini returned a partial analysis result.";
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
