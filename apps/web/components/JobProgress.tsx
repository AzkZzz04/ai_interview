"use client";

/**
 * Shared progress readout for any long-running background job.
 *
 * Both resume extraction and Gemini analysis report real backend stages, so
 * they get the same treatment: the current stage, an elapsed timer, and an
 * indeterminate bar. The live region announces stage changes to assistive tech.
 */
export function JobProgress({
  stage,
  elapsedSeconds,
  label
}: {
  stage: string;
  elapsedSeconds: number;
  label: string;
}) {
  return (
    <div className="job-progress" role="status" aria-live="polite">
      <div className="job-progress-header">
        <strong>{stage}</strong>
        <span>{elapsedSeconds}s</span>
      </div>
      <div className="progress-track indeterminate" aria-label={label}>
        <span />
      </div>
    </div>
  );
}
