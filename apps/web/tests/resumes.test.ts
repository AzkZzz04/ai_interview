import { afterEach, describe, expect, it, vi } from "vitest";

import { getCurrentResume, uploadResume } from "@/lib/api/resumes";

describe("resume API", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it("treats a 404 from /current as no resume yet", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 404 })));

    await expect(getCurrentResume()).resolves.toBeNull();
  });

  it("returns the current resume payload when present", async () => {
    const payload = { id: "resume-1", normalizedText: "Java", chunks: [] };
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(
      JSON.stringify(payload),
      { status: 200, headers: { "content-type": "application/json" } }
    )));

    await expect(getCurrentResume()).resolves.toMatchObject({ id: "resume-1" });
  });

  it("surfaces a non-404 error from /current as a structured error", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ code: "INTERNAL_ERROR", message: "boom" }),
      { status: 500, headers: { "content-type": "application/json" } }
    )));

    await expect(getCurrentResume()).rejects.toMatchObject({
      name: "JobApiError",
      status: 500,
      code: "INTERNAL_ERROR"
    });
  });

  it("returns the accepted job for a successful upload", async () => {
    const accepted = { jobId: "job-1", jobType: "RESUME_EXTRACTION", status: "QUEUED" };
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(
      JSON.stringify(accepted),
      { status: 202, headers: { "content-type": "application/json" } }
    )));

    const file = new File(["resume bytes"], "resume.pdf", { type: "application/pdf" });
    await expect(uploadResume(file)).resolves.toMatchObject({ jobId: "job-1" });
  });

  it("maps an upload failure to a structured HTTP error", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ code: "UPLOAD_TOO_LARGE", message: "too big" }),
      { status: 413, headers: { "content-type": "application/json" } }
    )));

    const file = new File(["x"], "resume.pdf", { type: "application/pdf" });
    await expect(uploadResume(file)).rejects.toMatchObject({
      status: 413,
      code: "UPLOAD_TOO_LARGE"
    });
  });
});
