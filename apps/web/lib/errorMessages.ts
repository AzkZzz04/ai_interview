import { ApiError } from "@/lib/api/client";
import { JobApiError, JobError } from "@/lib/api/jobs";

const NETWORK_MESSAGE = "The server is not reachable. Check your connection and try again.";

const ERROR_MESSAGES: Record<string, string> = {
  GEMINI_NOT_CONFIGURED: "The AI service is not configured.",
  GEMINI_RATE_LIMITED: "The AI service is at its usage limit. Try again later.",
  GEMINI_TIMEOUT: "The AI request timed out.",
  GEMINI_UPSTREAM_ERROR: "The AI service is temporarily unavailable.",
  GEMINI_SAFETY: "The AI could not complete this request because of its content policy. Edit the text and try again.",
  GEMINI_RECITATION: "The AI stopped because the response repeated source text. Try again.",
  GEMINI_MAX_TOKENS: "The AI response was too long to complete. Try shorter input.",
  GEMINI_EMPTY_RESPONSE: "The AI returned an empty response. Try again.",
  GEMINI_INVALID_RESPONSE: "The AI returned a result that could not be read. Try again.",
  REFERENCE_MISMATCH: "The selected document no longer matches the edited text. Submit the edited text again.",
  RESUME_NOT_FOUND: "The selected resume is no longer available.",
  RESUME_NOT_READY: "The selected resume has not finished processing.",
  RESUME_REFERENCE_REQUIRED: "The background job does not contain a resume reference.",
  RESUME_TEXT_REQUIRED: "Paste resume text or upload a resume before continuing.",
  RESUME_EXTRACTION_FAILED: "The resume text could not be extracted.",
  RESUME_PARSER_BUSY: "The resume parser is busy. Try the upload again shortly.",
  JOB_DESCRIPTION_NOT_FOUND: "The selected job description is no longer available.",
  JOB_NOT_FOUND: "The background job is no longer available.",
  REQUEST_TIMEOUT: "The request timed out.",
  INVALID_REQUEST: "The request is invalid.",
  UPLOAD_TOO_LARGE: "The uploaded resume exceeds the configured size limit.",
  RATE_LIMITED: "Too many requests were submitted. Try again shortly.",
  SERVICE_UNAVAILABLE: "The backend service is temporarily unavailable.",
  NOT_FOUND: "The requested resource is no longer available.",
  CONFLICT: "The request conflicts with the current resource state.",
  UNPROCESSABLE_CONTENT: "The submitted content could not be processed.",
  PROCESSING_ERROR: "The background job could not be processed.",
  INTERNAL_ERROR: "The backend could not complete the request.",
  REQUEST_FAILED: "The request failed."
};

export function errorCode(error: unknown): string | null {
  if (error instanceof JobApiError || error instanceof ApiError) {
    return error.code;
  }
  if (isJobError(error)) {
    return error.code;
  }
  return null;
}

export function friendlyError(error: unknown, fallback = "The request failed.") {
  const code = errorCode(error);
  if (code && ERROR_MESSAGES[code]) {
    return ERROR_MESSAGES[code];
  }
  if (error instanceof JobApiError || error instanceof ApiError) {
    if (error.kind === "NETWORK") {
      return NETWORK_MESSAGE;
    }
    if (error.kind === "TIMEOUT") {
      return ERROR_MESSAGES.REQUEST_TIMEOUT;
    }
    return fallback;
  }
  if (isJobError(error)) {
    return fallback;
  }
  return error instanceof Error
    ? error.message || fallback
    : typeof error === "string" ? error : fallback;
}

function isJobError(error: unknown): error is JobError {
  return Boolean(
    error &&
    typeof error === "object" &&
    "message" in error &&
    "code" in error
  );
}
