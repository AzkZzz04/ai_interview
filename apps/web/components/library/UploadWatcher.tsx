import { useEffect } from "react";
import { ApiError } from "@/lib/api/client";
import { isTerminal } from "@/lib/api/types";
import type { ResumeExtractionResult } from "@/lib/api/types";
import { useJob } from "@/lib/query/useJob";
import { DuplicateNotice } from "./DuplicateNotice";

/** Follows a new upload's extraction job; if its text matched a saved resume, shows the duplicate notice. */
export function UploadWatcher({ jobId, onDismiss }: { jobId: string; onDismiss: () => void }) {
  const { job, error } = useJob<ResumeExtractionResult>(jobId);
  const duplicateOf = job?.result?.duplicateOf;
  useEffect(() => {
    if (duplicateOf) return;
    if (job && isTerminal(job.status)) {
      onDismiss();
      return;
    }
    if (error instanceof ApiError && !error.retryable) onDismiss();
  }, [duplicateOf, error, job, onDismiss]);
  if (!duplicateOf) return null;
  return <DuplicateNotice kind="resumes" id={duplicateOf.id} name={duplicateOf.name} max={80} onDismiss={onDismiss} />;
}
