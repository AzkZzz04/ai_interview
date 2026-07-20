import { createIdempotencyKey } from "@/lib/api/idempotency";
import { JobAcceptedResponse, responseMessage } from "@/lib/api/jobs";

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://127.0.0.1:8080";
const REQUEST_TIMEOUT_MS = 15_000;

export type AiAnalysisPayload = {
  resumeText: string;
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
  return postJson<JobAcceptedResponse>("/api/analyses", payload);
}

export async function createAiAnswerFeedback(payload: AnswerFeedbackPayload): Promise<JobAcceptedResponse> {
  return postJson<JobAcceptedResponse>("/api/interview/feedback", payload);
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
      const message = await responseMessage(response);
      throw new Error(message);
    }

    return response.json() as Promise<TResponse>;
  }
  catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") {
      throw new Error("The job could not be submitted within 15 seconds. Check the API connection and try again.");
    }
    throw error;
  }
  finally {
    window.clearTimeout(timeoutId);
  }
}
