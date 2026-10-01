import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { clearLinkedInRecovery, readLinkedInRecovery, writeLinkedInRecovery } from "@/lib/linkedInRecovery";
import { saveLastPair } from "@/lib/lastPair";
import { renderRoute, RESUME_TEXT, JOB_TEXT, setupMockBackend } from "./render";

const { store, server } = setupMockBackend();

const LINKEDIN_TEXT = "Staff Engineer\nLed the API design and migration for customer data, reducing errors by 35%.\n\nBackend Engineer\nBuilt retry-safe job processing with durable PostgreSQL storage.";

describe("resume library", () => {
  it("blocks saving without a name", async () => {
    const user = userEvent.setup();
    renderRoute("/library/resumes");
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
    renderRoute("/library/resumes");
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
    renderRoute("/library/resumes");
    await user.click(await screen.findByRole("button", { name: "Delete Doomed" }));
    const dialog = await screen.findByRole("alertdialog");
    expect(await within(dialog).findByText("1 score")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Delete" }));
    await waitFor(() => expect(screen.queryByText("Doomed")).not.toBeInTheDocument());
    expect(() => store.getResume(resume.id)).toThrow();
  });
});

describe("LinkedIn experience recovery", () => {
	const openDialog = async (user: ReturnType<typeof userEvent.setup>) => {
		await user.click((await screen.findAllByRole("button", { name: "Paste LinkedIn experience" }))[0]);
	};

	it("restores a split and removed-item choices after reload without saving until confirmed", async () => {
		clearLinkedInRecovery();
		const user = userEvent.setup();
		const firstPage = renderRoute("/library/experiences");
		await openDialog(user);
		await user.type(screen.getByLabelText("Experience text"), LINKEDIN_TEXT);
		await user.click(screen.getByRole("button", { name: "Split into items" }));
		await screen.findByRole("button", { name: "Save 2 items" }, { timeout: 6_000 });
		await user.click(screen.getByRole("button", { name: "Remove Staff Engineer" }));
		await waitFor(() => expect(readLinkedInRecovery()?.removed).toEqual([0]));
		expect(store.listExperiences().items).toHaveLength(0);
		firstPage.unmount();

		const secondPage = renderRoute("/library/experiences");
		expect(await screen.findByText("Backend Engineer")).toBeInTheDocument();
		expect(screen.queryByText("Staff Engineer")).not.toBeInTheDocument();
		expect(store.listExperiences().items).toHaveLength(0);
		await user.click(screen.getByRole("button", { name: "Back" }));
		expect(screen.getByLabelText("Experience text")).toHaveValue(LINKEDIN_TEXT);
		secondPage.unmount();
		clearLinkedInRecovery();
	});

	it("does not resubmit an expired split automatically and lets the user retry with retained text", async () => {
		clearLinkedInRecovery();
		writeLinkedInRecovery({ jobId: "missing-job", text: LINKEDIN_TEXT, removed: [1] });
		let splitRequests = 0;
		server.use(
			http.get("*/api/jobs/missing-job", () => HttpResponse.json({ code: "JOB_NOT_FOUND", message: "The background job is no longer available." }, { status: 404 })),
			http.post("*/api/experiences/linkedin-split", async ({ request }) => {
				splitRequests += 1;
				const body = await request.json() as { text: string };
				return HttpResponse.json(store.splitLinkedIn(body.text), { status: 202 });
			})
		);

		const user = userEvent.setup();
		renderRoute("/library/experiences");
		expect(await screen.findByText(/saved text is still available/i)).toBeInTheDocument();
		expect(screen.getByLabelText("Experience text")).toHaveValue(LINKEDIN_TEXT);
		expect(splitRequests).toBe(0);
		await user.click(screen.getByRole("button", { name: "Split again" }));
		await screen.findByRole("button", { name: "Save 2 items" }, { timeout: 6_000 });
		expect(splitRequests).toBe(1);
		expect(readLinkedInRecovery()?.removed).toEqual([]);
	});

	it("clears the recovery draft after save or an explicit discard", async () => {
		clearLinkedInRecovery();
		const user = userEvent.setup();
		renderRoute("/library/experiences");
		await openDialog(user);
		await user.type(screen.getByLabelText("Experience text"), LINKEDIN_TEXT);
		await waitFor(() => expect(readLinkedInRecovery()?.text).toBe(LINKEDIN_TEXT));
		await user.click(screen.getByRole("button", { name: "Discard" }));
		expect(readLinkedInRecovery()).toBeNull();

		await openDialog(user);
		await user.type(screen.getByLabelText("Experience text"), LINKEDIN_TEXT);
		await user.click(screen.getByRole("button", { name: "Split into items" }));
		await screen.findByRole("button", { name: "Save 2 items" }, { timeout: 6_000 });
		await user.click(screen.getByRole("button", { name: "Save 2 items" }));
		await waitFor(() => expect(readLinkedInRecovery()).toBeNull());
		expect(store.listExperiences().items).toHaveLength(2);
	});
});

describe("home", () => {
  it("offers Continue only while the stored resume exists", async () => {
    const resume = store.pasteResume({ name: "Kept", jobTitle: null, text: RESUME_TEXT }).body.resume;
    const job = store.createTargetJob({ name: "Acme", text: JOB_TEXT }).body.targetJob;
    saveLastPair({ resumeId: resume.id, targetJobId: job.id });
    const first = renderRoute("/");
    expect(await screen.findByRole("link", { name: /continue/i })).toHaveAttribute("href", `/flow/${resume.id}/jobs/${job.id}`);
    first.unmount();

    store.deleteResume(resume.id);
    renderRoute("/");
    expect(await screen.findByRole("link", { name: /start/i })).toBeInTheDocument();
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(screen.queryByRole("link", { name: /continue/i })).not.toBeInTheDocument();
  });
});
