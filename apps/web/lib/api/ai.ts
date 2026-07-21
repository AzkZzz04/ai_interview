import { createIdempotencyKey } from "@/lib/api/idempotency";
import { JobAcceptedResponse, JobApiError, responseError } from "@/lib/api/jobs";

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://127.0.0.1:8080";
const REQUEST_TIMEOUT_MS = 15_000;

export type AiAnalysisPayload = {
  resumeId: string | null;
  resumeText: string;
  jobDescriptionId: string | null;
  jobDescription: string;
  targetRole: string;
  seniority: string;
};

export type AnswerFeedbackPayload = AiAnalysisPayload & {
  questionText: string;
  category: string;
  expectedSignals: string[];
  answerText: string;
};

export async function createAiAnalysis(payload: AiAnalysisPayload): Promise<JobAcceptedResponse> {
  return postJson<JobAcceptedResponse>("/api/analyses", analysisRequest(payload));
}

export async function createAiAnswerFeedback(payload: AnswerFeedbackPayload): Promise<JobAcceptedResponse> {
  return postJson<JobAcceptedResponse>("/api/interview/feedback", feedbackRequest(payload));
}

export function analysisRequest(payload: AiAnalysisPayload) {
  return {
    ...(payload.resumeId ? { resumeId: payload.resumeId } : { resumeText: payload.resumeText }),
    ...(payload.jobDescriptionId
      ? { jobDescriptionId: payload.jobDescriptionId }
      : payload.jobDescription.trim() ? { jobDescription: payload.jobDescription } : {}),
    targetRole: payload.targetRole,
    seniority: payload.seniority
  };
}

export function feedbackRequest(payload: AnswerFeedbackPayload) {
  return {
    ...analysisRequest(payload),
    questionText: payload.questionText,
    category: payload.category,
    expectedSignals: payload.expectedSignals,
    answerText: payload.answerText
  };
}

async function postJson<TResponse>(path: string, body: unknown): Promise<TResponse> {
  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

  try {
    const idempotencyKey = createIdempotencyKey(path);
    const response = await fetch(`${API_BASE_URL}${path}`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Idempotency-Key": idempotencyKey
      },
      body: JSON.stringify(body),
      signal: controller.signal
    });

    if (!response.ok) {
      const error = await responseError(response);
      throw new JobApiError(error.message, response.status, false, "HTTP", error.code);
    }

    return response.json() as Promise<TResponse>;
  }
  catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") {
      throw new JobApiError(
        "The job could not be submitted within 15 seconds. Check the API connection and try again.",
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
