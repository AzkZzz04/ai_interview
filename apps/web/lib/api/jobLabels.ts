import type { JobStage } from "./types";

export const STAGE_LABELS: Record<JobStage, string> = {
  QUEUED: "Waiting to start",
  READING_FILE: "Reading the file",
  EXTRACTING_TEXT: "Extracting text",
  NORMALIZING_TEXT: "Cleaning up the text",
  CHUNKING_TEXT: "Finding sections",
  SCORING_RESUME: "Scoring the resume",
  MATCHING_JOB: "Comparing with the job",
  RETRIEVING_EXPERIENCE: "Looking through your experience",
  MATCHING_EXPERIENCE: "Matching experience to the job",
  GENERATING_QUESTIONS: "Choosing questions",
  SPLITTING_EXPERIENCE: "Splitting your experience",
  SCORING_ANSWER: "Scoring your answer",
  COMPLETED: "Done"
};
