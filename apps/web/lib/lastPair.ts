// The last-used resume and target job: a browser convenience only (KTD7); the API stays the source of truth.
const KEY = "ai-interview:last-pair";

export type LastPair = { resumeId: string; targetJobId: string | null };

export function readLastPair(): LastPair | null {
  try {
    const value = JSON.parse(window.localStorage.getItem(KEY) ?? "null") as LastPair | null;
    return value && typeof value.resumeId === "string" ? value : null;
  }
  catch {
    return null;
  }
}

export function saveLastPair(pair: LastPair) {
  try {
    window.localStorage.setItem(KEY, JSON.stringify(pair));
  }
  catch {
    // Storage can be unavailable (private mode); the pair is only a convenience.
  }
}
