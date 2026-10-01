import { API_BASE_URL } from "@/lib/api/config";

// Job IDs contain no resume text. Keep them per API environment so a local API and production API never mix.
export const RESUME_UPLOAD_JOBS_KEY = `ai-interview:resume-upload-jobs:${API_BASE_URL || "same-origin"}`;

export function readResumeUploadJobs(): string[] {
  try {
    const value: unknown = JSON.parse(window.localStorage.getItem(RESUME_UPLOAD_JOBS_KEY) ?? "[]");
    return Array.isArray(value) ? [...new Set(value.filter((item): item is string => typeof item === "string" && item.length > 0))] : [];
  }
  catch {
    return [];
  }
}

export function saveResumeUploadJobs(jobIds: string[]) {
  try {
    if (jobIds.length === 0) window.localStorage.removeItem(RESUME_UPLOAD_JOBS_KEY);
    else window.localStorage.setItem(RESUME_UPLOAD_JOBS_KEY, JSON.stringify([...new Set(jobIds)]));
  }
  catch {
    // Recovery is a browser convenience; uploads continue when storage is unavailable.
  }
}
