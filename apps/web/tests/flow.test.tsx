import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import ResumePickerPage from "@/app/flow/page";
import ScorePage from "@/app/flow/[resumeId]/page";
import FitPage from "@/app/flow/[resumeId]/jobs/[jobId]/page";
import { JOB_TEXT, RESUME_TEXT, renderWithClient, setupMockBackend } from "./render";
import { navigation, router } from "./setup";

const { store, server } = setupMockBackend();
const settle = () => new Promise((resolve) => setTimeout(resolve, 40));

function paste(name = "Backend", text = RESUME_TEXT) {
  return store.pasteResume({ name, jobTitle: "Backend Engineer", text }).body.resume;
}

describe("resume picker", () => {
  it("disables resumes that are still processing", async () => {
    paste("Ready one");
    paste("Still processing", `${RESUME_TEXT} other`);
    // Test jobs finish in milliseconds, so pin one resume in PROCESSING.
    server.use(http.get("*/api/resumes", () => HttpResponse.json({
      items: store.listResumes().items.map((resume) =>
        resume.name === "Still processing" ? { ...resume, status: "PROCESSING" } : resume)
    })));
    renderWithClient(<ResumePickerPage />);
    expect(await screen.findByRole("button", { name: /still processing/i })).toBeDisabled();
    expect(screen.getByRole("button", { name: /ready one/i })).toBeEnabled();
  });
});

describe("score step", () => {
  it("shows a saved score after reload without starting a job", async () => {
    const resume = paste();
    store.scoreResume(resume.id);
    await settle();
    const scoreSpy = vi.spyOn(store, "scoreResume");
    navigation.params = { resumeId: resume.id };
    renderWithClient(<ScorePage />);
    expect(await screen.findByText("Overall score")).toBeInTheDocument();
    await settle();
    expect(scoreSpy).not.toHaveBeenCalled();
  });

  it("shows a failed score with retry and keeps the job title", async () => {
    const resume = paste();
    store.failNext("RESUME_SCORE", { retryable: false });
    navigation.params = { resumeId: resume.id };
    const user = userEvent.setup();
    renderWithClient(<ScorePage />);
    const alert = await screen.findByRole("alert", {}, { timeout: 4_000 });
    expect(alert).toHaveTextContent(/content policy/i);
    expect(screen.getByLabelText(/job title/i)).toHaveValue("Backend Engineer");

    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText("Overall score", {}, { timeout: 4_000 })).toBeInTheDocument();
  });

  it("shows the deleted state for a deleted resume", async () => {
    const resume = paste();
    store.deleteResume(resume.id);
    navigation.params = { resumeId: resume.id };
    renderWithClient(<ScorePage />);
    expect(await screen.findByRole("heading", { name: "This resume was deleted" })).toBeInTheDocument();
  });
});

describe("fit step", () => {
  function pair() {
    const resume = paste();
    const job = store.createTargetJob({ name: "Acme", text: JOB_TEXT }).body.targetJob;
    navigation.params = { resumeId: resume.id, jobId: job.id };
    return { resume, job };
  }

  it("invites adding experience and sends no suggestions request without other sources", async () => {
    pair();
    const suggestSpy = vi.spyOn(store, "runSuggestions");
    renderWithClient(<FitPage />);
    expect(await screen.findByText("Add past work to get suggestions")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /add a project/i })).toBeInTheDocument();
    await settle();
    expect(suggestSpy).not.toHaveBeenCalled();
  });

  it("offers Refresh when suggestions are stale", async () => {
    const { resume, job } = pair();
    paste("Older", `${RESUME_TEXT} older`);
    store.runSuggestions(resume.id, job.id);
    await settle();
    store.getSuggestions(resume.id, job.id);
    store.createExperience({ title: "Side project", description: "Built a sample tool." });
    renderWithClient(<FitPage />);
    expect(await screen.findByRole("button", { name: /refresh/i })).toBeInTheDocument();
  });

  it("opens the pair's practice set from the mode chooser", async () => {
    pair();
    const user = userEvent.setup();
    renderWithClient(<FitPage />);
    const practice = await screen.findByRole("button", { name: "Practice this job" });
    await waitFor(() => expect(practice).toBeEnabled(), { timeout: 4_000 });
    expect(screen.getByText("Coming soon")).toBeInTheDocument();
    await user.click(practice);
    await waitFor(() => expect(router.push).toHaveBeenCalledWith(expect.stringMatching(/^\/practice\/[\w-]+$/)));
  });
});
