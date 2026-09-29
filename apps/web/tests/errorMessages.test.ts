import { describe, expect, it } from "vitest";
import { friendlyError } from "@/lib/errorMessages";
import { JobApiError } from "@/lib/api/jobs";
import { ApiError } from "@/lib/api/client";

describe("fixed error messages", () => {
  it("maps job errors by code instead of provider message text", () => {
    expect(friendlyError({
      code: "GEMINI_RATE_LIMITED",
      message: "unrelated and changeable provider wording",
      retryable: false
    })).toBe("The AI service is at its usage limit. Try again later.");
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

  it("names no vendors in any message", () => {
    const codes = ["GEMINI_NOT_CONFIGURED", "GEMINI_RATE_LIMITED", "GEMINI_SAFETY", "GEMINI_TIMEOUT"];
    for (const code of codes) {
      expect(friendlyError({ code, message: "x", retryable: false })).not.toMatch(/gemini|spring|object storage/i);
    }
    expect(friendlyError(new JobApiError("x", null, true, "NETWORK"))).not.toMatch(/spring/i);
  });

  it("maps the new ApiError by code and kind", () => {
    expect(friendlyError(new ApiError("x", "HTTP", 429, "RATE_LIMITED", true))).toContain("Too many requests");
    expect(friendlyError(new ApiError("x", "NETWORK"))).toBe("The server is not reachable. Check your connection and try again.");
    expect(friendlyError(new ApiError("x", "TIMEOUT"))).toBe("The request timed out.");
  });
});
