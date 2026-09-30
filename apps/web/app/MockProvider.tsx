import { useEffect, useState, type ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { API_MOCKS, type MockMode } from "@/lib/api/config";

type Status = "starting" | "ready" | "failed";

/** Starts the mock worker before rendering children, so the first API request is already intercepted. Fails closed. */
export function MockProvider({ children, mode = API_MOCKS }: { children: ReactNode; mode?: MockMode }) {
  const [status, setStatus] = useState<Status>(mode === "off" ? "ready" : "starting");

  useEffect(() => {
    if (mode === "off" || status !== "starting") return;
    let cancelled = false;
    // Loaded lazily so production builds with mocks off never fetch msw.
    import("@/mocks/browser")
      .then(({ startMockWorker }) => startMockWorker(mode))
      .then(
        () => !cancelled && setStatus("ready"),
        () => !cancelled && setStatus("failed")
      );
    return () => {
      cancelled = true;
    };
  }, [mode, status]);

  if (status === "ready") return children;
  if (status === "starting") return null;
  return (
    <div role="alert" className="mx-auto flex max-w-md flex-col items-center gap-4 py-16 text-center">
      <h1 className="text-xl font-semibold">Mock data could not start</h1>
      <p className="text-muted-foreground">The app runs in mock mode and will not call the real API without it.</p>
      <Button onClick={() => setStatus("starting")}>Retry</Button>
    </div>
  );
}

export function MockBadge() {
  if (API_MOCKS === "off") return null;
  return (
    <span className="rounded-full border border-warning/40 bg-warning/10 px-2 py-0.5 text-xs font-medium text-foreground">
      Mock data
    </span>
  );
}
