import { setupWorker } from "msw/browser";
import type { JobType } from "@/lib/api/types";
import { createHandlers } from "./handlers";
import { createMockStore } from "./store";

let started: Promise<void> | null = null;

/** Idempotent: React strict mode runs the starting effect twice. */
export function startMockWorker(mode: "all" | "new-only") {
  started ??= start(mode);
  return started;
}

async function start(mode: "all" | "new-only") {
  const store = createMockStore({ storage: window.localStorage });
  // Console controls for manual testing, e.g. __mockApi.failNext("JOB_FIT", { retryable: false }).
  Object.assign(window, {
    __mockApi: {
      failNext: (type: JobType, options?: { retryable?: boolean; code?: string }) => store.failNext(type, options),
      reset: () => store.reset()
    }
  });
  await setupWorker(...createHandlers(store, mode)).start({ onUnhandledFrame: "bypass", quiet: true });
}
