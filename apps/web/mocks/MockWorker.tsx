"use client";

import { useEffect } from "react";
import { startMockWorker } from "./browser";

/** Browser-only: loaded with ssr: false because msw/browser has no server build. */
export default function MockWorker({ mode, onReady }: { mode: "all" | "new-only"; onReady: () => void }) {
  useEffect(() => {
    void startMockWorker(mode).then(onReady);
  }, [mode, onReady]);
  return null;
}
