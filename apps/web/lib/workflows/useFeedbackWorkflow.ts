"use client";

import { MutableRefObject, useEffect, useRef, useState } from "react";
import { friendlyError } from "@/lib/errorMessages";
import { isTerminalJob, JobStage } from "@/lib/api/jobs";
import { useJobPolling } from "@/lib/useJobPolling";
import { AnswerFeedback, scoreAnswer } from "@/lib/mockAssessment";
import type { PendingFeedbackInfo } from "@/lib/workflows/types";
import { useLatest } from "@/lib/workflows/useLatest";

type Options = {
  questionCount: number;
  questionSetIdRef: MutableRefObject<string | null>;
  restoreContext: (pending: PendingFeedbackInfo) => void;
  setFeedback: (questionId: string, feedback: AnswerFeedback) => void;
  setNotice: (notice: string | null) => void;
  captureInputRefs: (resumeId: string | null, jobDescriptionId: string | null) => void;
};

export function useFeedbackWorkflow(options: Options) {
  const optionsRef = useLatest(options);
  const polling = useJobPolling<AnswerFeedback, PendingFeedbackInfo>("ai-interview:job:feedback");
  const [isSubmittingAnswer, setIsSubmittingAnswer] = useState(false);
  const hydratedGenerationRef = useRef<string | null>(null);

  useEffect(() => {
    const current = optionsRef.current;
    const pending = polling.context;
    if (
      pending &&
      polling.restored &&
      polling.generation !== hydratedGenerationRef.current &&
      !current.questionSetIdRef.current &&
      current.questionCount === 0
    ) {
      current.restoreContext(pending);
      hydratedGenerationRef.current = polling.generation;
    }

    const refs = polling.job?.inputRefs;
    if (refs) {
      current.captureInputRefs(refs.resumeId, refs.jobDescriptionId);
    }

    if (polling.terminalError) {
      setIsSubmittingAnswer(false);
      if (pending && contextMatches(current.questionSetIdRef.current, pending)) {
        current.setFeedback(pending.questionId, scoreAnswer(pending.answer));
        current.setNotice(`Feedback job could not be recovered: ${friendlyError(polling.terminalError)} Local draft feedback is shown.`);
      }
      else {
        current.setNotice("Feedback job could not be recovered because its submitted question context is unavailable.");
      }
      polling.finish();
      return;
    }

    if (!polling.activeJobId) {
      return;
    }
    const job = polling.job;
    if (!job || !isTerminalJob(job.status)) {
      setIsSubmittingAnswer(true);
      current.setNotice(polling.connectionError
        ? "Connection interrupted. The feedback job is still running and polling will continue."
        : job ? jobStageLabel(job.stage) : "Waiting for the feedback worker");
      return;
    }

    setIsSubmittingAnswer(false);
    if (!pending || !contextMatches(current.questionSetIdRef.current, pending)) {
      current.setNotice("Feedback completed for an earlier question set and was ignored.");
      polling.finish();
      return;
    }
    if ((job.status === "SUCCEEDED" || job.status === "PARTIAL") && job.result) {
      current.setFeedback(pending.questionId, job.result);
      current.setNotice(job.status === "PARTIAL" ? "Gemini returned partial feedback." : null);
      polling.finish();
      return;
    }
    current.setFeedback(pending.questionId, scoreAnswer(pending.answer));
    current.setNotice(`Gemini feedback unavailable: ${friendlyError(job.error)} Local draft feedback is shown.`);
    polling.finish();
  }, [optionsRef, polling.activeJobId, polling.connectionError, polling.context, polling.finish, polling.generation, polling.job, polling.restored, polling.terminalError]);

  return {
    ...polling,
    isSubmittingAnswer,
    setIsSubmittingAnswer
  };
}

function contextMatches(questionSetId: string | null, pending: PendingFeedbackInfo) {
  return questionSetId === pending.questionSetId;
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
