const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://127.0.0.1:8080";
const JOB_REQUEST_TIMEOUT_MS = 10_000;

export type JobType = "RESUME_EXTRACTION" | "ANALYSIS" | "ANSWER_FEEDBACK";
export type JobStatus = "QUEUED" | "PROCESSING" | "RETRYING" | "SUCCEEDED" | "PARTIAL" | "FAILED";
export type JobStage =
  | "QUEUED"
  | "READING_FILE"
  | "EXTRACTING_TEXT"
  | "NORMALIZING_TEXT"
  | "CHUNKING_TEXT"
  | "ASSESSING_RESUME"
  | "GENERATING_QUESTIONS"
  | "SCORING_ANSWER"
  | "COMPLETED";

export type JobAcceptedResponse = {
  jobId: string;
  jobType: JobType;
  status: JobStatus;
  stage: JobStage;
  statusUrl: string;
  reused: boolean;
};

export type JobError = {
  code: string | null;
  message: string;
  retryable: boolean | null;
};

export type JobStatusResponse<TResult> = {
  jobId: string;
  jobType: JobType;
  status: JobStatus;
  stage: JobStage;
  attempts: number;
  result: TResult | null;
  error: JobError | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
};

export type JobApiErrorKind = "HTTP" | "NETWORK" | "TIMEOUT" | "INVALID_RESPONSE";

export class JobApiError extends Error {
  constructor(
    message: string,
    readonly status: number | null,
    readonly retryable: boolean,
    readonly kind: JobApiErrorKind
  ) {
    super(message);
    this.name = "JobApiError";
  }
}

export function isTerminalJob(status: JobStatus) {
  return status === "SUCCEEDED" || status === "PARTIAL" || status === "FAILED";
}

export async function getJob<TResult>(
  jobId: string,
  options: { signal?: AbortSignal; timeoutMs?: number } = {}
): Promise<JobStatusResponse<TResult>> {
  const controller = new AbortController();
  let timedOut = false;
  const timeoutId = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, options.timeoutMs ?? JOB_REQUEST_TIMEOUT_MS);
  const abortFromCaller = () => controller.abort();
  options.signal?.addEventListener("abort", abortFromCaller, { once: true });

  try {
    const response = await fetch(`${API_BASE_URL}/api/jobs/${jobId}`, {
      cache: "no-store",
      signal: controller.signal
    });
    if (!response.ok) {
      let message = `Request failed with status ${response.status}`;
      try {
        message = await responseMessage(response);
      }
      catch {
        // The HTTP status still determines retry behavior when the error body is malformed.
      }
      throw new JobApiError(message, response.status, isRetryableStatus(response.status), "HTTP");
    }

    let body: unknown;
    try {
      body = await response.json();
    }
    catch {
      throw new JobApiError("The job service returned invalid JSON.", response.status, false, "INVALID_RESPONSE");
    }
    if (!isJobStatusResponse(body)) {
      throw new JobApiError("The job service returned an invalid job response.", response.status, false, "INVALID_RESPONSE");
    }
    return body as JobStatusResponse<TResult>;
  }
  catch (error) {
    if (error instanceof JobApiError) {
      throw error;
    }
    if (timedOut) {
      throw new JobApiError("The job status request timed out.", null, true, "TIMEOUT");
    }
    if (options.signal?.aborted) {
      throw error;
    }
    throw new JobApiError(
      error instanceof Error ? error.message : "Could not reach the job service.",
      null,
      true,
      "NETWORK"
    );
  }
  finally {
    clearTimeout(timeoutId);
    options.signal?.removeEventListener("abort", abortFromCaller);
  }
}

export function isRetryableJobApiError(error: unknown) {
  return error instanceof JobApiError ? error.retryable : true;
}

export async function responseMessage(response: Response) {
  const fallback = `Request failed with status ${response.status}`;
  const contentType = response.headers.get("content-type") ?? "";
  if (!contentType.includes("application/json")) {
    return (await response.text()) || fallback;
  }
  const body = (await response.json()) as { message?: string; error?: string; detail?: string };
  return body.message ?? body.detail ?? body.error ?? fallback;
}

function isRetryableStatus(status: number) {
  return status === 408 || status === 429 || status >= 500;
}

function isJobStatusResponse(value: unknown): value is JobStatusResponse<unknown> {
  if (!value || typeof value !== "object") {
    return false;
  }
  const candidate = value as Partial<JobStatusResponse<unknown>>;
  return (
    typeof candidate.jobId === "string" &&
    typeof candidate.jobType === "string" &&
    typeof candidate.status === "string" &&
    typeof candidate.stage === "string" &&
    typeof candidate.attempts === "number" &&
    typeof candidate.createdAt === "string"
  );
}
