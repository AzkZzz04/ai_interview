import type { AiAnalysisPayload, AnswerFeedbackPayload } from "@/lib/api/ai";
import type { EvidenceChunk } from "@/lib/evidence";
import type { Assessment, InterviewQuestion } from "@/lib/mockAssessment";

export type ResumeFileInfo = {
  name: string;
  size: number;
  extension: string;
};

export type ResumeJobContext = ResumeFileInfo & {
  startedAt: number;
  textFallback: string | null;
};

export type AnalysisJobResult = {
  assessment: Assessment | null;
  questions: {
    questions: InterviewQuestion[];
    modelProvider: string;
  } | null;
};

export type PendingFeedbackInfo = {
  analysis: AiAnalysisPayload;
  questions: InterviewQuestion[];
  questionSetId: string;
  questionId: string;
  answer: string;
  payload: AnswerFeedbackPayload;
};

export type ResumeEvidenceSource = {
  normalizedText: string;
  chunks: EvidenceChunk[];
};
