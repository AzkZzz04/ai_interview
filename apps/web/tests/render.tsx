import { QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { render } from "@testing-library/react";
import { afterAll, afterEach, beforeAll } from "vitest";
import { makeQueryClient } from "@/lib/query/queryClient";
import { createMockServer } from "@/mocks/server";
import { makeRouter } from "@/router";

/** Starts a mock backend for this test file. By default jobs finish within a few milliseconds of real time. */
export function setupMockBackend({ stageMs = 5 } = {}) {
  const backend = createMockServer({ queuedMs: 0, stageMs });
  beforeAll(() => backend.server.listen({ onUnhandledFrame: "error" }));
  afterEach(() => {
    backend.server.resetHandlers();
    backend.store.reset();
  });
  afterAll(() => backend.server.close());
  return backend;
}

/** Renders the real route tree at `path`, in memory history, with a fresh query client. */
export function renderRoute(path: string) {
  const client = makeQueryClient();
  const router = makeRouter(createMemoryHistory({ initialEntries: [path] }));
  return { client, router, ...render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>) };
}

export const RESUME_TEXT = "Sample resume text for tests. ".repeat(6);
export const JOB_TEXT = "Sample job description for a backend engineer. ".repeat(4);
