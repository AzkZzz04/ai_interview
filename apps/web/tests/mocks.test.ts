import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { apiRequest } from "@/lib/api/client";
import type {
  Attempt, DeleteImpact, History, JobAccepted, JobStatusResponse, PracticeSet, ResumeCreated, ResumeDetail,
  ResumeExtractionResult, TargetJobCreated
} from "@/lib/api/types";
import { createMockServer } from "@/mocks/server";
import { createMockStore, type MockStoreOptions } from "@/mocks/store";

let clock = Date.parse("2026-09-28T12:00:00Z");
const timing: MockStoreOptions = { now: () => clock, queuedMs: 100, stageMs: 100 };
const { store, server } = createMockServer(timing);
const finishJobs = () => { clock += 10_000; };

beforeAll(() => server.listen({ onUnhandledFrame: "error" }));
afterEach(() => store.reset());
afterAll(() => server.close());

const RESUME_TEXT = "Sample resume text. ".repeat(10);
const JOB_TEXT = "Sample job description for a backend engineer. ".repeat(5);
const post = <T,>(path: string, body: unknown = {}) => apiRequest<T>(path, { method: "POST", body, retries: 0 });
const get = <T,>(path: string) => apiRequest<T>(path, { retries: 0 });
const errorOf = (promise: Promise<unknown>) => promise.then(() => null, (error: unknown) => error);

async function pasteResume(name = "Backend", text = RESUME_TEXT) {
  return (await post<ResumeCreated>("/api/resumes/paste", { name, jobTitle: null, text })).resume;
}

async function targetJob() {
  return (await post<TargetJobCreated>("/api/target-jobs", { name: "Acme", text: JOB_TEXT })).targetJob;
}

describe("mock API", () => {
  it("resolves a pasted duplicate to the saved resume", async () => {
    const saved = await pasteResume("First");
    const again = await post<ResumeCreated>("/api/resumes/paste", { name: "Second", jobTitle: null, text: `  ${RESUME_TEXT}  ` });
    expect(again).toMatchObject({ duplicate: true, resume: { id: saved.id, name: "First" } });
  });

  // Upload rules are tested on the store: jsdom's File loses its name inside the fetch FormData, so multipart
  // parsing in the handler is covered by the browser smoke run instead.
  it("resolves an uploaded file whose text matches a saved resume to that resume", async () => {
    const saved = await pasteResume("Pasted");
    const upload = store.uploadResume({ fileName: "resume.txt", size: RESUME_TEXT.length, content: RESUME_TEXT, name: "Uploaded" });
    expect(upload).toMatchObject({ status: 202, body: { duplicate: false, resume: { status: "PROCESSING" } } });

    finishJobs();
    const job = await get<JobStatusResponse<ResumeExtractionResult>>(`/api/jobs/${upload.body.resume.activeJob!.jobId}`);
    expect(job.status).toBe("SUCCEEDED");
    expect(job.result?.duplicateOf).toEqual({ id: saved.id, name: "Pasted" });
    expect(await errorOf(get(`/api/resumes/${upload.body.resume.id}`))).toMatchObject({ status: 404, code: "RESUME_NOT_FOUND" });
  });

  it("returns the same resume at once when the same file is uploaded twice, and rejects other file types", () => {
    const file = { fileName: "cv.pdf", size: 11, content: "%PDF sample" };
    const first = store.uploadResume(file);
    expect(store.uploadResume(file)).toMatchObject({ status: 200, body: { duplicate: true, resume: { id: first.body.resume.id } } });
    expect(() => store.uploadResume({ ...file, fileName: "cv.png" })).toThrow(expect.objectContaining({ code: "UNSUPPORTED_FILE_TYPE" }));
  });

  it("previews and cascades a resume delete", async () => {
    const resume = await pasteResume();
    const other = await pasteResume("Other", `${RESUME_TEXT} other`);
    const job = await targetJob();
    await post(`/api/resumes/${resume.id}/score`);
    await post(`/api/resumes/${resume.id}/target-jobs/${job.id}/fit`);
    await post(`/api/resumes/${resume.id}/target-jobs/${job.id}/suggestions`);
    await post(`/api/resumes/${other.id}/target-jobs/${job.id}/suggestions`);
    const set = await post<PracticeSet>("/api/practice-sets", { resumeId: resume.id, targetJobId: job.id, mode: "PRACTICE" });
    finishJobs();
    const ready = await get<PracticeSet>(`/api/practice-sets/${set.id}`);
    await post(`/api/practice-sets/${set.id}/questions/${ready.questions[0].id}/attempts`, { text: "My first answer" });

    const preview = await get<DeleteImpact>(`/api/resumes/${resume.id}/delete-impact`);
    expect(preview).toEqual({ scores: 1, fits: 1, suggestionSets: 1, practiceSets: 1, attempts: 1, staleSuggestionSets: 1 });

    await apiRequest(`/api/resumes/${resume.id}`, { method: "DELETE" });
    expect(await errorOf(get(`/api/resumes/${resume.id}`))).toMatchObject({ code: "RESUME_NOT_FOUND" });
    expect(await errorOf(get(`/api/practice-sets/${set.id}`))).toMatchObject({ code: "PRACTICE_SET_NOT_FOUND" });
    expect(await errorOf(get(`/api/jobs/${set.activeJob!.jobId}`))).toMatchObject({ code: "JOB_NOT_FOUND" });
    const suggestions = await get<{ stale: boolean }>(`/api/resumes/${other.id}/target-jobs/${job.id}/suggestions`);
    expect(suggestions.stale).toBe(true);
    const history = await get<History>("/api/history");
    expect(history.resumes.map((item) => item.id)).toEqual([other.id]);
    expect(history.practiceSets).toEqual([]);
  });

  it("returns a practice set at once and fills 3-8 questions when its job succeeds", async () => {
    const resume = await pasteResume();
    const job = await targetJob();
    const body = { resumeId: resume.id, targetJobId: job.id, mode: "PRACTICE" };
    const created = await post<PracticeSet>("/api/practice-sets", body);
    expect(created).toMatchObject({ status: "GENERATING", questions: [], activeJob: { jobType: "PRACTICE_QUESTIONS" } });

    finishJobs();
    const ready = await get<PracticeSet>(`/api/practice-sets/${created.id}`);
    expect(ready.status).toBe("READY");
    expect(ready.questions.length).toBeGreaterThanOrEqual(3);
    expect(ready.questions.length).toBeLessThanOrEqual(8);
    expect(ready.questions[0].rationale).toBeTruthy();
    expect((await post<PracticeSet>("/api/practice-sets", body)).id).toBe(created.id);
  });

  it("scores attempts, reports the change, and rejects an unchanged answer", async () => {
    const resume = await pasteResume();
    const job = await targetJob();
    const set = await post<PracticeSet>("/api/practice-sets", { resumeId: resume.id, targetJobId: job.id, mode: "PRACTICE" });
    finishJobs();
    const question = (await get<PracticeSet>(`/api/practice-sets/${set.id}`)).questions[0];
    const path = `/api/practice-sets/${set.id}/questions/${question.id}/attempts`;

    await post<Attempt>(path, { text: "Short answer" });
    finishJobs();
    expect(await errorOf(post(path, { text: " Short answer " }))).toMatchObject({ status: 409, code: "ANSWER_UNCHANGED" });
    expect(await errorOf(post(path, { text: "   " }))).toMatchObject({ status: 400, code: "ANSWER_EMPTY" });

    const second = await post<Attempt>(path, { text: "A much longer answer with context, the action I took and a measured result." });
    expect(second).toMatchObject({ number: 2, status: "PENDING" });
    finishJobs();
    const attempts = (await get<PracticeSet>(`/api/practice-sets/${set.id}`)).questions[0].attempts;
    expect(attempts.map((attempt) => attempt.status)).toEqual(["SCORED", "SCORED"]);
    expect(attempts[1].scoreDelta).toBe(attempts[1].feedback!.score - attempts[0].feedback!.score);
  });

  it("fails a forced job with the documented error shape and keeps the attempt for retry", async () => {
    const resume = await pasteResume();
    store.failNext("RESUME_SCORE", { retryable: false });
    const accepted = await post<JobAccepted>(`/api/resumes/${resume.id}/score`);
    finishJobs();
    const job = await get<JobStatusResponse>(`/api/jobs/${accepted.jobId}`);
    expect(job).toMatchObject({ status: "FAILED", attempts: 1, maxAttempts: 3, result: null });
    expect(job.error).toEqual({ code: "GEMINI_SAFETY", message: "Simulated failure", retryable: false });
  });

  it("shows a retrying job before a retryable failure", async () => {
    const resume = await pasteResume();
    store.failNext("RESUME_SCORE");
    const accepted = await post<JobAccepted>(`/api/resumes/${resume.id}/score`);
    clock += 250;
    expect(await get<JobStatusResponse>(`/api/jobs/${accepted.jobId}`)).toMatchObject({ status: "RETRYING", attempts: 2 });
    finishJobs();
    expect(await get<JobStatusResponse>(`/api/jobs/${accepted.jobId}`)).toMatchObject({ status: "FAILED", attempts: 3 });
  });

  it("keeps a resume, its score and a pending job across a reload from storage", async () => {
    const storage = new Map<string, string>();
    const local = {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => void storage.set(key, value),
      removeItem: (key: string) => void storage.delete(key)
    };
    const first = createMockStore({ ...timing, storage: local });
    const { body } = first.pasteResume({ name: "Kept", jobTitle: null, text: RESUME_TEXT });
    const scored = first.scoreResume(body.resume.id);
    finishJobs();
    first.getResume(body.resume.id);
    const pending = first.runFit(body.resume.id, first.createTargetJob({ name: "Acme", text: JOB_TEXT }).body.targetJob.id);

    const reloaded = createMockStore({ ...timing, storage: local });
    const detail: ResumeDetail = reloaded.getResume(body.resume.id);
    expect(detail.score).not.toBeNull();
    expect(detail.activeJob?.jobId).toBe(scored.jobId);
    expect(reloaded.getJob(pending.jobId).status).toBe("QUEUED");
    finishJobs();
    expect(reloaded.getJob(pending.jobId).status).toBe("SUCCEEDED");
  });

  it("marks the score stale when the job title changes", async () => {
    const resume = await pasteResume();
    await post(`/api/resumes/${resume.id}/score`);
    finishJobs();
    const updated = await apiRequest<{ latestScore: { stale: boolean } }>(`/api/resumes/${resume.id}`, {
      method: "PATCH", body: { jobTitle: "Staff Engineer" }
    });
    expect(updated.latestScore.stale).toBe(true);
  });

  it("refuses suggestions without other sources", async () => {
    const resume = await pasteResume();
    const job = await targetJob();
    const view = await get<{ sourcesAvailable: boolean }>(`/api/resumes/${resume.id}/target-jobs/${job.id}/suggestions`);
    expect(view.sourcesAvailable).toBe(false);
    expect(await errorOf(post(`/api/resumes/${resume.id}/target-jobs/${job.id}/suggestions`)))
      .toMatchObject({ status: 409, code: "NO_EXPERIENCE_SOURCES" });
  });
});
