import { API_BASE_URL, API_MOCKS } from "./api/config";

export type LinkedInRecovery = {
  jobId: string | null;
  text: string;
  removed: number[];
};

const key = () => {
  const origin = typeof window === "undefined" ? "server" : window.location.origin;
  return `ai-interview:linkedin-split:v1:${encodeURIComponent(origin)}:${encodeURIComponent(API_BASE_URL || "same-origin")}:${API_MOCKS}`;
};

/** Browser-only recovery is a convenience; a missing stored job never submits a replacement. */
export function readLinkedInRecovery(): LinkedInRecovery | null {
  try {
    const value = JSON.parse(window.localStorage.getItem(key()) ?? "null") as Partial<LinkedInRecovery> & { version?: number } | null;
    if (
      !value ||
      value.version !== 1 ||
      typeof value.text !== "string" ||
      !(value.jobId === null || typeof value.jobId === "string") ||
      !Array.isArray(value.removed) ||
      !value.removed.every((index) => Number.isInteger(index) && index >= 0)
    ) return null;
    return { jobId: value.jobId, text: value.text, removed: value.removed };
  }
  catch {
    return null;
  }
}

export function writeLinkedInRecovery(recovery: LinkedInRecovery) {
  try {
    if (!recovery.text && !recovery.jobId) window.localStorage.removeItem(key());
    else window.localStorage.setItem(key(), JSON.stringify({ version: 1, ...recovery }));
  }
  catch {
    // Storage can be unavailable; the split can still be reviewed in the current tab.
  }
}

export function clearLinkedInRecovery() {
  try {
    window.localStorage.removeItem(key());
  }
  catch {
    // Storage can be unavailable; this is only a recovery convenience.
  }
}
