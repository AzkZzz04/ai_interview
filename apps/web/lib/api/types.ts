// Platform types shared by the API client and the job hooks.
// Resource types from docs/api/frontend-api-contract.md are added alongside the mocks.

export type ApiErrorBody = { code: string | null; message: string };

export type JobType =
  | "RESUME_EXTRACTION"
  | "RESUME_SCORE"
  | "JOB_FIT"
  | "EXPERIENCE_SUGGESTIONS"
  | "PRACTICE_QUESTIONS"
  | "EXPERIENCE_SPLIT"
  | "ANSWER_FEEDBACK";

export type JobStatus = "QUEUED" | "PROCESSING" | "RETRYING" | "SUCCEEDED" | "PARTIAL" | "FAILED";

export type JobStage =
  | "QUEUED"
  | "READING_FILE"
  | "EXTRACTING_TEXT"
  | "NORMALIZING_TEXT"
  | "CHUNKING_TEXT"
  | "SCORING_RESUME"
  | "MATCHING_JOB"
  | "RETRIEVING_EXPERIENCE"
  | "MATCHING_EXPERIENCE"
  | "GENERATING_QUESTIONS"
  | "SPLITTING_EXPERIENCE"
  | "SCORING_ANSWER"
  | "COMPLETED";

export type JobError = { code: string | null; message: string; retryable: boolean | null };

export type JobInputRefs = {
  resumeId: string | null;
  targetJobId: string | null;
  practiceSetId: string | null;
  attemptId: string | null;
};

export type ActiveJob = {
  jobId: string;
  jobType: JobType;
  status: JobStatus;
  stage: JobStage;
  attempts: number;
  maxAttempts: number;
  error: JobError | null;
};

export type JobAccepted = {
  jobId: string;
  jobType: JobType;
  status: JobStatus;
  stage: JobStage;
  statusUrl: string;
  reused: boolean;
  inputRefs: JobInputRefs;
};

export type JobStatusResponse<TResult = unknown> = {
  jobId: string;
  jobType: JobType;
  status: JobStatus;
  stage: JobStage;
  attempts: number;
  maxAttempts: number;
  result: TResult | null;
  error: JobError | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  inputRefs: JobInputRefs;
};

export function isTerminal(status: JobStatus | undefined) {
  return status === "SUCCEEDED" || status === "PARTIAL" || status === "FAILED";
}
