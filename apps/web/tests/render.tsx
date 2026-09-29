import { QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import type { ReactElement } from "react";
import { afterAll, afterEach, beforeAll } from "vitest";
import { makeQueryClient } from "@/lib/query/queryClient";
import { createMockServer } from "@/mocks/server";

/** Starts a mock backend for this test file. Jobs finish within a few milliseconds of real time. */
export function setupMockBackend() {
  const backend = createMockServer({ queuedMs: 0, stageMs: 5 });
  beforeAll(() => backend.server.listen({ onUnhandledFrame: "error" }));
  afterEach(() => {
    backend.server.resetHandlers();
    backend.store.reset();
  });
  afterAll(() => backend.server.close());
  return backend;
}

export function renderWithClient(ui: ReactElement) {
  const client = makeQueryClient();
  return { client, ...render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>) };
}

export const RESUME_TEXT = "Sample resume text for tests. ".repeat(6);
export const JOB_TEXT = "Sample job description for a backend engineer. ".repeat(4);
