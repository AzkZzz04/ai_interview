import { JobApiError, JobError } from "@/lib/api/jobs";

const ERROR_MESSAGES: Record<string, string> = {
  GEMINI_NOT_CONFIGURED: "Gemini key is not configured.",
  GEMINI_RATE_LIMITED: "Gemini quota is exhausted for the configured key and model.",
  GEMINI_TIMEOUT: "The AI request timed out.",
  GEMINI_UPSTREAM_ERROR: "Gemini is temporarily unavailable.",
  GEMINI_SAFETY: "Gemini could not complete this request because of its safety policy.",
  GEMINI_RECITATION: "Gemini stopped the response because it detected recited content.",
  GEMINI_MAX_TOKENS: "Gemini reached its response limit before completing the result.",
  GEMINI_EMPTY_RESPONSE: "Gemini returned an empty response.",
  GEMINI_INVALID_RESPONSE: "Gemini returned a response that could not be validated.",
  REFERENCE_MISMATCH: "The selected document no longer matches the edited text. Submit the edited text again.",
  RESUME_NOT_FOUND: "The selected resume is no longer available.",
  RESUME_NOT_READY: "The selected resume has not finished processing.",
  RESUME_REFERENCE_REQUIRED: "The background job does not contain a resume reference.",
  RESUME_TEXT_REQUIRED: "Paste resume text or upload a resume before continuing.",
  RESUME_EXTRACTION_FAILED: "The resume text could not be extracted.",
  RESUME_PARSER_BUSY: "The resume parser is busy. Try the upload again shortly.",
  JOB_DESCRIPTION_NOT_FOUND: "The selected job description is no longer available.",
  JOB_NOT_FOUND: "The background job is no longer available.",
  REQUEST_TIMEOUT: "The backend request timed out.",
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
  if (error instanceof JobApiError) {
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
  if (error instanceof JobApiError) {
    if (error.kind === "NETWORK") {
      return "Spring Boot API is not reachable.";
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
