"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import {
  getJob,
  isRetryableJobApiError,
  isTerminalJob,
  JobAcceptedResponse,
  JobApiError,
  JobStatusResponse
} from "@/lib/api/jobs";
import {
  ActiveJobWorkflow,
  clearJobWorkflow,
  isCurrentWorkflowGeneration,
  JobWorkflowStorage,
  persistJobWorkflow,
  restoreJobWorkflow
} from "@/lib/jobWorkflow";

export function useJobPolling<TResult, TContext = never>(storageKey: string) {
  const [active, setActive] = useState<ActiveJobWorkflow<TContext> | null>(null);
  const [job, setJob] = useState<JobStatusResponse<TResult> | null>(null);
  const [connectionError, setConnectionError] = useState<string | null>(null);
  const [terminalError, setTerminalError] = useState<JobApiError | null>(null);
  const activeRef = useRef<ActiveJobWorkflow<TContext> | null>(null);
  const requestControllerRef = useRef<AbortController | null>(null);

  useEffect(() => {
    const restored = safelyRestore<TContext>(storageKey);
    if (restored) {
      activeRef.current = restored;
      setActive(restored);
    }
  }, [storageKey]);

  useEffect(() => {
    if (!active) {
      return;
    }

    const { jobId, generation } = active;
    let cancelled = false;
    let timerId: number | null = null;
    let pollCount = 0;

    const isCurrent = () => (
      !cancelled && isCurrentWorkflowGeneration(activeRef.current, generation)
    );

    const scheduleNextPoll = () => {
      pollCount += 1;
      timerId = window.setTimeout(poll, pollCount < 10 ? 1_000 : 3_000);
    };

    const poll = async () => {
      const controller = new AbortController();
      requestControllerRef.current = controller;
      try {
        const next = await getJob<TResult>(jobId, { signal: controller.signal });
        if (!isCurrent()) {
          return;
        }
		setJob(next);
		setConnectionError(null);
		if (isTerminalJob(next.status)) {
			return;
		}
        scheduleNextPoll();
      }
      catch (error) {
        if (!isCurrent()) {
          return;
        }
        if (isRetryableJobApiError(error)) {
          setConnectionError(error instanceof Error ? error.message : "Could not refresh job status");
          scheduleNextPoll();
          return;
        }

        safelyClear(storageKey, generation);
        setConnectionError(null);
        setTerminalError(asJobApiError(error));
      }
    };

    void poll();
    return () => {
      cancelled = true;
      controllerAbort(requestControllerRef.current);
      if (timerId !== null) {
        window.clearTimeout(timerId);
      }
    };
  }, [active, storageKey]);

  const start = useCallback((accepted: JobAcceptedResponse, context: TContext | null = null) => {
    controllerAbort(requestControllerRef.current);
    const next = safelyPersist(storageKey, accepted, context);
    activeRef.current = next;
    setActive(next);
    setJob(null);
    setConnectionError(null);
    setTerminalError(null);
    return next.generation;
  }, [storageKey]);

  const finish = useCallback(() => {
    const current = activeRef.current;
    if (current) {
      safelyClear(storageKey, current.generation);
    }
    controllerAbort(requestControllerRef.current);
    activeRef.current = null;
    setActive(null);
    setJob(null);
    setConnectionError(null);
    setTerminalError(null);
  }, [storageKey]);

  return {
    activeJobId: active?.jobId ?? null,
    generation: active?.generation ?? null,
    context: active?.context ?? null,
    restored: active?.restored ?? false,
    job,
    connectionError,
    terminalError,
    start,
    finish,
    clear: finish
  };
}

function browserStorage(): JobWorkflowStorage {
  return { local: window.localStorage, session: window.sessionStorage };
}

function safelyRestore<TContext>(storageKey: string) {
  try {
    return restoreJobWorkflow<TContext>(storageKey, browserStorage());
  }
  catch {
    return null;
  }
}

function safelyPersist<TContext>(
  storageKey: string,
  accepted: JobAcceptedResponse,
  context: TContext | null
) {
  try {
    return persistJobWorkflow(storageKey, accepted, context, browserStorage());
  }
  catch {
    return {
      schemaVersion: 1 as const,
      jobId: accepted.jobId,
      jobType: accepted.jobType,
      generation: `${Date.now()}-${Math.random().toString(36).slice(2)}`,
      startedAt: new Date().toISOString(),
      context,
      restored: false
    };
  }
}

function safelyClear(storageKey: string, generation: string) {
  try {
    clearJobWorkflow(storageKey, generation, browserStorage());
  }
  catch {
    // Storage can be unavailable in privacy-restricted browsing contexts.
  }
}

function asJobApiError(error: unknown) {
  return error instanceof JobApiError
    ? error
    : new JobApiError(
        error instanceof Error ? error.message : "Could not refresh job status",
        null,
        false,
        "INVALID_RESPONSE"
      );
}

function controllerAbort(controller: AbortController | null) {
  if (controller && !controller.signal.aborted) {
    controller.abort();
  }
}
