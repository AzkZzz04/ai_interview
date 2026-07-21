import { createIdempotencyKey } from "@/lib/api/idempotency";
import { JobAcceptedResponse, JobApiError, responseError } from "@/lib/api/jobs";

export type ResumeUploadResponse = {
  id: string;
  originalFilename: string;
  contentType: string | null;
  detectedContentType: string | null;
  sizeBytes: number;
  rawTextLength: number;
  normalizedTextLength: number;
  normalizedText: string;
  chunks: Array<{
    index: number;
    section: string;
    content: string;
    characterCount: number;
  }>;
  processedAt: string;
};

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://127.0.0.1:8080";

export async function uploadResume(file: File): Promise<JobAcceptedResponse> {
  const formData = new FormData();
  formData.append("file", file);
  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), 30_000);

  try {
    const idempotencyKey = createIdempotencyKey("resume-upload");
    const response = await fetch(`${API_BASE_URL}/api/resumes`, {
      method: "POST",
      headers: {
        "Idempotency-Key": idempotencyKey
      },
      body: formData,
      signal: controller.signal
    });

    if (!response.ok) {
      const error = await responseError(response);
      throw new JobApiError(error.message, response.status, false, "HTTP", error.code);
    }

    return response.json() as Promise<JobAcceptedResponse>;
  }
  catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") {
      throw new JobApiError(
        "The resume could not be uploaded within 30 seconds. Check object storage and try again.",
        null,
        true,
        "TIMEOUT",
        "REQUEST_TIMEOUT"
      );
    }
    throw error;
  }
  finally {
    window.clearTimeout(timeoutId);
  }
}

export async function getCurrentResume(): Promise<ResumeUploadResponse | null> {
  const response = await fetch(`${API_BASE_URL}/api/resumes/current`);

  if (response.status === 404) {
    return null;
  }

  if (!response.ok) {
    const error = await responseError(response);
    throw new JobApiError(error.message, response.status, false, "HTTP", error.code);
  }

  return response.json() as Promise<ResumeUploadResponse>;
}
