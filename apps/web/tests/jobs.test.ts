import { afterEach, describe, expect, it, vi } from "vitest";
import { getJob, JobApiError } from "@/lib/api/jobs";

describe("job API", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it("classifies 404 as a terminal recovery error", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ message: "Job not found" }),
      { status: 404, headers: { "content-type": "application/json" } }
    )));

    await expect(getJob("missing-job")).rejects.toMatchObject({
      name: "JobApiError",
      status: 404,
      retryable: false,
      kind: "HTTP"
    });
  });

  it("keeps a malformed 404 body terminal", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(
      "not-json",
      { status: 404, headers: { "content-type": "application/json" } }
    )));

    await expect(getJob("missing-job")).rejects.toMatchObject({
      status: 404,
      retryable: false,
      kind: "HTTP"
    });
  });

  it("classifies 429 and server failures as retryable", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("busy", { status: 429 })));

    await expect(getJob("busy-job")).rejects.toMatchObject({
      status: 429,
      retryable: true
    });
  });

  it("aborts a hanging status request after the configured timeout", async () => {
    vi.useFakeTimers();
    vi.stubGlobal("fetch", vi.fn((_url, init) => new Promise((_resolve, reject) => {
      (init?.signal as AbortSignal).addEventListener("abort", () => {
        reject(new DOMException("Aborted", "AbortError"));
      });
    })));

    const request = getJob("slow-job", { timeoutMs: 50 });
    const rejection = expect(request).rejects.toEqual(expect.objectContaining<Partial<JobApiError>>({
      name: "JobApiError",
      retryable: true,
      kind: "TIMEOUT"
    }));
    await vi.advanceTimersByTimeAsync(50);

    await rejection;
  });
});
