import { describe, expect, it } from "vitest";

import { createIdempotencyKey } from "@/lib/api/idempotency";

describe("idempotency keys", () => {
  it("prefixes the key with the caller scope", () => {
    expect(createIdempotencyKey("resume-upload")).toMatch(/^resume-upload:/);
    expect(createIdempotencyKey("analysis")).toMatch(/^analysis:/);
  });

  it("produces a unique suffix on every call", () => {
    const keys = new Set(Array.from({ length: 50 }, () => createIdempotencyKey("analysis")));

    expect(keys.size).toBe(50);
  });
});
