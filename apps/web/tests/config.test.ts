import { describe, expect, it } from "vitest";
import { parseMockMode } from "@/lib/api/config";

describe("parseMockMode", () => {
  it("defaults to mocks on in development and off in production", () => {
    expect(parseMockMode(undefined, false)).toBe("all");
    expect(parseMockMode(undefined, true)).toBe("off");
    expect(parseMockMode("bogus", true)).toBe("off");
  });

  it("honors an explicit mode in any build", () => {
    expect(parseMockMode("all", true)).toBe("all");
    expect(parseMockMode("off", false)).toBe("off");
  });

  it("treats the removed new-only mode as unknown", () => {
    expect(parseMockMode("new-only", false)).toBe("all");
    expect(parseMockMode("new-only", true)).toBe("off");
  });
});
