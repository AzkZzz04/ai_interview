"use client";

import type { ResumeExtractionResult } from "@/lib/api/types";
import { useJob } from "@/lib/query/useJob";
import { DuplicateNotice } from "./DuplicateNotice";

/** Follows a new upload's extraction job; if its text matched a saved resume, shows the duplicate notice. */
export function UploadWatcher({ jobId, onDismiss }: { jobId: string; onDismiss: () => void }) {
  const { job } = useJob<ResumeExtractionResult>(jobId);
  const duplicateOf = job?.result?.duplicateOf;
  if (!duplicateOf) return null;
  return <DuplicateNotice kind="resumes" id={duplicateOf.id} name={duplicateOf.name} max={80} onDismiss={onDismiss} />;
}
