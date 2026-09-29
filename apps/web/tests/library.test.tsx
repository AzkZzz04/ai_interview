import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import HomePage from "@/app/page";
import ResumesPage from "@/app/library/resumes/page";
import { saveLastPair } from "@/lib/lastPair";
import { renderWithClient, RESUME_TEXT, JOB_TEXT, setupMockBackend } from "./render";

const { store, server } = setupMockBackend();

describe("resume library", () => {
  it("blocks saving without a name", async () => {
    const user = userEvent.setup();
    renderWithClient(<ResumesPage />);
    await user.click((await screen.findAllByRole("button", { name: /add resume/i }))[0]);
    await user.click(screen.getByRole("button", { name: "Save resume" }));
    expect(screen.getByText("Give this resume a name.")).toBeInTheDocument();
    expect(screen.getByText("Choose a file to upload.")).toBeInTheDocument();
  });

  it("turns an upload that matches a saved resume into a duplicate notice", async () => {
    const saved = store.pasteResume({ name: "Backend", jobTitle: null, text: RESUME_TEXT }).body.resume;
    // jsdom files lose their name in the fetch FormData, so the upload handler is replaced for this test.
    server.use(http.post("*/api/resumes", () => {
      const { status, body } = store.uploadResume({ fileName: "copy.txt", size: RESUME_TEXT.length, content: RESUME_TEXT, name: "Copy" });
      return HttpResponse.json(body, { status });
    }));
    const user = userEvent.setup();
    renderWithClient(<ResumesPage />);
    await user.click((await screen.findAllByRole("button", { name: /add resume/i }))[0]);
    await user.upload(screen.getByLabelText("File"), new File([RESUME_TEXT], "copy.txt", { type: "text/plain" }));
    await user.click(screen.getByRole("button", { name: "Save resume" }));

    const notice = await screen.findByText(/already saved as/i, {}, { timeout: 4_000 });
    const box = notice.closest("[role=status]") as HTMLElement;
    expect(within(box).getByRole("link", { name: "Backend" })).toHaveAttribute("href", `#item-${saved.id}`);
    expect(within(box).getByRole("button", { name: "Rename Backend" })).toBeInTheDocument();
  });

  it("previews what a delete removes, then removes the row", async () => {
    const resume = store.pasteResume({ name: "Doomed", jobTitle: null, text: RESUME_TEXT }).body.resume;
    store.scoreResume(resume.id);
    await new Promise((resolve) => setTimeout(resolve, 20));
    const user = userEvent.setup();
    renderWithClient(<ResumesPage />);
    await user.click(await screen.findByRole("button", { name: "Delete Doomed" }));
    const dialog = await screen.findByRole("alertdialog");
    expect(await within(dialog).findByText("1 score")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Delete" }));
    await waitFor(() => expect(screen.queryByText("Doomed")).not.toBeInTheDocument());
    expect(() => store.getResume(resume.id)).toThrow();
  });
});

describe("home", () => {
  it("offers Continue only while the stored resume exists", async () => {
    const resume = store.pasteResume({ name: "Kept", jobTitle: null, text: RESUME_TEXT }).body.resume;
    const job = store.createTargetJob({ name: "Acme", text: JOB_TEXT }).body.targetJob;
    saveLastPair({ resumeId: resume.id, targetJobId: job.id });
    const first = renderWithClient(<HomePage />);
    expect(await screen.findByRole("link", { name: /continue/i })).toHaveAttribute("href", `/flow/${resume.id}/jobs/${job.id}`);
    first.unmount();

    store.deleteResume(resume.id);
    renderWithClient(<HomePage />);
    expect(await screen.findByRole("link", { name: /start/i })).toBeInTheDocument();
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(screen.queryByRole("link", { name: /continue/i })).not.toBeInTheDocument();
  });
});
