import { describe, expect, it } from "vitest";
import { friendlyError } from "@/lib/errorMessages";
import { JobApiError } from "@/lib/api/jobs";

describe("fixed error messages", () => {
  it("maps job errors by code instead of provider message text", () => {
    expect(friendlyError({
      code: "GEMINI_RATE_LIMITED",
      message: "unrelated and changeable provider wording",
      retryable: false
    })).toBe("Gemini quota is exhausted for the configured key and model.");
  });

  it("maps structured submission errors by code", () => {
    expect(friendlyError(new JobApiError(
      "server wording",
      409,
      false,
      "HTTP",
      "REFERENCE_MISMATCH"
    ))).toContain("no longer matches");
  });

	  it("does not display mutable backend wording for unknown codes", () => {
	    expect(friendlyError(new JobApiError(
	      "provider response contained internal details",
	      502,
	      false,
	      "HTTP",
	      "UNKNOWN_BACKEND_CODE"
	    ))).toBe("The request failed.");
	  });
});
