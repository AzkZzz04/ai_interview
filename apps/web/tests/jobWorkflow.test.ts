import { describe, expect, it } from "vitest";
import type { JobAcceptedResponse } from "@/lib/api/jobs";
import {
  clearJobWorkflow,
  contextStorageKey,
  persistJobWorkflow,
  restoreJobWorkflow
} from "@/lib/jobWorkflow";

const accepted: JobAcceptedResponse = {
  jobId: "job-1",
  jobType: "ANALYSIS",
  status: "QUEUED",
  stage: "QUEUED",
  statusUrl: "/api/jobs/job-1",
  reused: false,
  inputRefs: { resumeId: null, jobDescriptionId: null }
};

describe("job workflow persistence", () => {
  it("keeps only non-sensitive metadata in localStorage and the snapshot in sessionStorage", () => {
    const snapshot = {
      resumeText: "private resume text",
      jobDescription: "private job description"
    };

    const workflow = persistJobWorkflow(
      "analysis-job",
      accepted,
      snapshot,
      { local: window.localStorage, session: window.sessionStorage },
      "generation-1"
    );

    expect(window.localStorage.getItem("analysis-job")).not.toContain("private resume text");
    expect(window.sessionStorage.getItem(contextStorageKey("analysis-job", "generation-1")))
      .toContain("private resume text");
    expect(restoreJobWorkflow<typeof snapshot>(
      "analysis-job",
      { local: window.localStorage, session: window.sessionStorage }
    )).toMatchObject({
      jobId: "job-1",
      generation: "generation-1",
      context: snapshot,
      restored: true
    });
    expect(workflow.restored).toBe(false);
  });

  it("does not let an old generation clear a newer job", () => {
    persistJobWorkflow(
      "analysis-job",
      accepted,
      { resumeText: "first" },
      { local: window.localStorage, session: window.sessionStorage },
      "generation-1"
    );
    persistJobWorkflow(
      "analysis-job",
      { ...accepted, jobId: "job-2" },
      { resumeText: "second" },
      { local: window.localStorage, session: window.sessionStorage },
      "generation-2"
    );

    clearJobWorkflow(
      "analysis-job",
      "generation-1",
      { local: window.localStorage, session: window.sessionStorage }
    );

    expect(restoreJobWorkflow<{ resumeText: string }>(
      "analysis-job",
      { local: window.localStorage, session: window.sessionStorage }
    )).toMatchObject({
      jobId: "job-2",
      generation: "generation-2",
      context: { resumeText: "second" }
    });
  });

  it("restores legacy plain job IDs without inventing a fallback snapshot", () => {
    window.localStorage.setItem("analysis-job", "legacy-job");

    expect(restoreJobWorkflow(
      "analysis-job",
      { local: window.localStorage, session: window.sessionStorage }
    )).toMatchObject({
      jobId: "legacy-job",
      generation: "legacy-legacy-job",
      context: null,
      restored: true
    });
  });

	  it("does not publish new metadata when the private snapshot cannot be stored", () => {
	    persistJobWorkflow(
	      "analysis-job",
	      accepted,
	      { resumeText: "first" },
	      { local: window.localStorage, session: window.sessionStorage },
	      "generation-1"
	    );
	    const failingSession: Storage = {
	      get length() { return window.sessionStorage.length; },
	      clear: () => window.sessionStorage.clear(),
	      getItem: (key) => window.sessionStorage.getItem(key),
	      key: (index) => window.sessionStorage.key(index),
	      removeItem: (key) => window.sessionStorage.removeItem(key),
	      setItem: () => { throw new DOMException("Quota exceeded", "QuotaExceededError"); }
	    };

	    expect(() => persistJobWorkflow(
	      "analysis-job",
	      { ...accepted, jobId: "job-2" },
	      { resumeText: "second" },
	      { local: window.localStorage, session: failingSession },
	      "generation-2"
	    )).toThrow();

	    expect(restoreJobWorkflow<{ resumeText: string }>(
	      "analysis-job",
	      { local: window.localStorage, session: window.sessionStorage }
	    )).toMatchObject({
	      jobId: "job-1",
	      generation: "generation-1",
	      context: { resumeText: "first" }
	    });
	  });
});
