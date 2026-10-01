import { setupWorker } from "msw/browser";
import type { JobType } from "@/lib/api/types";
import { createHandlers } from "./handlers";
import { createMockStore } from "./store";

let started: Promise<void> | null = null;

/** Idempotent: React strict mode runs the starting effect twice. */
export function startMockWorker() {
  // A failed start is forgotten so Retry can try again.
  started ??= start().catch((error: unknown) => {
    started = null;
    throw error;
  });
  return started;
}

async function start() {
  const store = createMockStore({ storage: window.localStorage });
  // Console controls for manual testing, e.g. __mockApi.failNext("JOB_FIT", { retryable: false }).
  Object.assign(window, {
    __mockApi: {
      failNext: (type: JobType, options?: { retryable?: boolean; code?: string }) => store.failNext(type, options),
      reset: () => store.reset()
    }
  });
  await setupWorker(...createHandlers(store)).start({ onUnhandledFrame: "bypass", quiet: true });
}
