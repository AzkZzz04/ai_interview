import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  getJob,
  JobAcceptedResponse,
  JobApiError,
  JobStatusResponse
} from "@/lib/api/jobs";
import { useJobPolling } from "@/lib/useJobPolling";

vi.mock("@/lib/api/jobs", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/jobs")>();
  return { ...actual, getJob: vi.fn() };
});

type Result = { value: string };
type Context = { resumeText: string };

const firstAccepted = accepted("job-a");
const secondAccepted = accepted("job-b");

describe("useJobPolling", () => {
  beforeEach(() => {
    vi.mocked(getJob).mockReset();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("ignores a late result from a superseded generation", async () => {
    const first = deferred<JobStatusResponse<Result>>();
    const second = deferred<JobStatusResponse<Result>>();
    vi.mocked(getJob).mockImplementation((jobId) => (
      jobId === "job-a" ? first.promise : second.promise
    ));
    const { result } = renderHook(() => useJobPolling<Result, Context>("polling-job"));

    act(() => {
      result.current.start(firstAccepted, { resumeText: "first resume" });
    });
    await waitFor(() => expect(getJob).toHaveBeenCalledWith(
      "job-a",
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    ));

    act(() => {
      result.current.start(secondAccepted, { resumeText: "second resume" });
    });
    first.resolve(status("job-a", { value: "stale" }));
    second.resolve(status("job-b", { value: "current" }));

    await waitFor(() => expect(result.current.job?.jobId).toBe("job-b"));
    expect(result.current.job?.result).toEqual({ value: "current" });
    expect(result.current.context).toEqual({ resumeText: "second resume" });
  });

  it("stops polling and clears persisted state after a permanent error", async () => {
    vi.mocked(getJob).mockRejectedValue(
      new JobApiError("Job not found", 404, false, "HTTP")
    );
    const { result } = renderHook(() => useJobPolling<Result, Context>("polling-job"));

    act(() => {
      result.current.start(firstAccepted, { resumeText: "private resume" });
    });

    await waitFor(() => expect(result.current.terminalError?.status).toBe(404));
    expect(window.localStorage.getItem("polling-job")).toBeNull();
    expect([...sessionStorageKeys()]).toHaveLength(0);
    expect(getJob).toHaveBeenCalledTimes(1);
  });

  it("retries a transient provider or network error", async () => {
    vi.useFakeTimers();
    vi.mocked(getJob)
      .mockRejectedValueOnce(new JobApiError("Service unavailable", 503, true, "HTTP"))
      .mockResolvedValueOnce(status("job-a", { value: "recovered" }));
    const { result } = renderHook(() => useJobPolling<Result, Context>("polling-job"));

    await act(async () => {
      result.current.start(firstAccepted, { resumeText: "resume" });
      await Promise.resolve();
    });
    expect(getJob).toHaveBeenCalledTimes(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_000);
    });

    expect(getJob).toHaveBeenCalledTimes(2);
    expect(result.current.job?.result).toEqual({ value: "recovered" });
    expect(result.current.terminalError).toBeNull();
  });

  it("keeps a terminal result recoverable until the consumer finishes it", async () => {
    vi.mocked(getJob).mockResolvedValue(status("job-a", { value: "complete" }));
    const { result } = renderHook(() => useJobPolling<Result, Context>("polling-job"));

    act(() => {
      result.current.start(firstAccepted, { resumeText: "private resume" });
    });

    await waitFor(() => expect(result.current.job?.status).toBe("SUCCEEDED"));
    expect(window.localStorage.getItem("polling-job")).not.toBeNull();
    expect([...sessionStorageKeys()]).toHaveLength(1);

    act(() => {
      result.current.finish();
    });

    expect(window.localStorage.getItem("polling-job")).toBeNull();
    expect([...sessionStorageKeys()]).toHaveLength(0);
  });
});

function accepted(jobId: string): JobAcceptedResponse {
  return {
    jobId,
    jobType: "ANALYSIS",
    status: "QUEUED",
    stage: "QUEUED",
    statusUrl: `/api/jobs/${jobId}`,
    reused: false
  };
}

function status(jobId: string, result: Result): JobStatusResponse<Result> {
  return {
    jobId,
    jobType: "ANALYSIS",
    status: "SUCCEEDED",
    stage: "COMPLETED",
    attempts: 1,
    result,
    error: null,
    createdAt: new Date().toISOString(),
    startedAt: new Date().toISOString(),
    completedAt: new Date().toISOString()
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

function* sessionStorageKeys() {
  for (let index = 0; index < window.sessionStorage.length; index += 1) {
    const key = window.sessionStorage.key(index);
    if (key) {
      yield key;
    }
  }
}
