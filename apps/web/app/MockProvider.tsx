"use client";

import dynamic from "next/dynamic";
import { useState, type ReactNode } from "react";
import { API_MOCKS } from "@/lib/api/config";

const MockWorker = dynamic(() => import("@/mocks/MockWorker"), { ssr: false });

/** Starts the mock worker before rendering children, so the first API request is already intercepted. */
export function MockProvider({ children }: { children: ReactNode }) {
  const [ready, setReady] = useState(API_MOCKS === "off");
  if (API_MOCKS === "off") return children;
  return ready ? children : <MockWorker mode={API_MOCKS} onReady={() => setReady(true)} />;
}

export function MockBadge() {
  if (API_MOCKS === "off") return null;
  return (
    <span className="rounded-full border border-warning/40 bg-warning/10 px-2 py-0.5 text-xs font-medium text-foreground">
      Mock data
    </span>
  );
}
