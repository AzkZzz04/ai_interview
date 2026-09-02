"use client";

import { ArrowRight, FileText, Loader2, Sparkles, Upload } from "lucide-react";
import type { ChangeEvent, RefObject } from "react";

import { JobProgress } from "@/components/JobProgress";
import { SENIORITY_OPTIONS, seniorityFocus } from "@/lib/seniority";

export type UploadedResume = {
  name: string;
  size: number;
  extension: string;
  status: "extracting" | "ready" | "error";
  message: string;
};

export type ExtractionProgress = {
  startedAt: number;
  stage: string;
};

type ResumeInputPanelProps = {
  uploadedResume: UploadedResume | null;
  extractionProgress: ExtractionProgress | null;
  elapsedSeconds: number;
  targetRole: string;
  seniority: string;
  resumeText: string;
  jobDescription: string;
  analysisNotice: string | null;
  analysisStage: string | null;
  analysisElapsedSeconds: number;
  isAnalyzing: boolean;
  isUploadingResume: boolean;
  resumeTextareaRef: RefObject<HTMLTextAreaElement>;
  onResumeUpload: (event: ChangeEvent<HTMLInputElement>) => void;
  onRecoverLatestResume: () => void;
  onTargetRoleChange: (value: string) => void;
  onSeniorityChange: (value: string) => void;
  onResumeTextChange: (value: string) => void;
  onJobDescriptionChange: (value: string) => void;
  onRunAssessment: () => void;
};

export function ResumeInputPanel({
  uploadedResume,
  extractionProgress,
  elapsedSeconds,
  targetRole,
  seniority,
  resumeText,
  jobDescription,
  analysisNotice,
  analysisStage,
  analysisElapsedSeconds,
  isAnalyzing,
  isUploadingResume,
  resumeTextareaRef,
  onResumeUpload,
  onRecoverLatestResume,
  onTargetRoleChange,
  onSeniorityChange,
  onResumeTextChange,
  onJobDescriptionChange,
  onRunAssessment
}: ResumeInputPanelProps) {
  return (
    <div className="panel input-panel" id="resume">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">Resume input</p>
          <h2>Source material</h2>
        </div>
        <label className="icon-button">
          <Upload size={18} aria-hidden="true" />
          <input
            type="file"
            aria-label="Upload resume"
            accept=".pdf,.doc,.docx,.txt,.text,.md,application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document,text/plain,text/markdown"
            onChange={onResumeUpload}
            disabled={isUploadingResume || isAnalyzing}
          />
        </label>
      </div>

      {uploadedResume ? (
        <div className={`upload-summary ${uploadedResume.status}`}>
          {uploadedResume.status === "extracting" ? (
            <Loader2 className="spin" size={18} aria-hidden="true" />
          ) : (
            <FileText size={18} aria-hidden="true" />
          )}
          <div>
            <strong>{uploadedResume.name}</strong>
            <span>
              {formatFileSize(uploadedResume.size)} · {uploadedResume.message}
            </span>
            {uploadedResume.status === "error" ? (
              <button
                className="recover-upload-button"
                type="button"
                onClick={onRecoverLatestResume}
                disabled={isAnalyzing}
              >
                Use backend text
              </button>
            ) : null}
          </div>
        </div>
      ) : null}

      {isUploadingResume && extractionProgress ? (
        <JobProgress
          stage={extractionProgress.stage}
          elapsedSeconds={elapsedSeconds}
          label="Resume extraction is in progress"
        />
      ) : null}

      <label className="field">
        <span>Target role</span>
        <input
          value={targetRole}
          onChange={(event) => onTargetRoleChange(event.target.value)}
          disabled={isAnalyzing}
        />
      </label>

      <label className="field">
        <span>Seniority</span>
        <select
          aria-label="Seniority"
          value={seniority}
          onChange={(event) => onSeniorityChange(event.target.value)}
          disabled={isAnalyzing}
        >
          {SENIORITY_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>{option.value}</option>
          ))}
        </select>
        <span aria-live="polite">{seniorityFocus(seniority)}</span>
      </label>

      <label className="field">
        <span>Resume text</span>
        <textarea
          ref={resumeTextareaRef}
          className="resume-textarea"
          placeholder={
            isUploadingResume
              ? "Extracting resume text..."
              : "Paste resume text, or upload PDF, DOC, DOCX, TXT, or Markdown."
          }
          value={resumeText}
          onChange={(event) => onResumeTextChange(event.target.value)}
          disabled={isUploadingResume || isAnalyzing}
        />
      </label>

      <label className="field">
        <span>Job description</span>
        <textarea
          className="jd-textarea"
          placeholder="Paste a job description to make scoring and questions role-specific."
          value={jobDescription}
          onChange={(event) => onJobDescriptionChange(event.target.value)}
          disabled={isAnalyzing}
        />
      </label>

      {isAnalyzing && analysisStage ? (
        <JobProgress
          stage={analysisStage}
          elapsedSeconds={analysisElapsedSeconds}
          label="Resume analysis is in progress"
        />
      ) : null}

      {analysisNotice ? (
        <div className="ai-notice" role="status">
          {analysisNotice}
        </div>
      ) : null}

      <button className="primary-button" type="button" onClick={onRunAssessment} disabled={isAnalyzing || isUploadingResume}>
        {isAnalyzing ? <Loader2 className="spin" size={18} aria-hidden="true" /> : <Sparkles size={18} aria-hidden="true" />}
        {isAnalyzing ? "Analyzing with Gemini" : "Analyze resume"}
        <ArrowRight size={16} aria-hidden="true" />
      </button>
    </div>
  );
}

function formatFileSize(size: number) {
  if (size < 1024) {
    return `${size} B`;
  }
  if (size < 1024 * 1024) {
    return `${Math.round(size / 1024)} KB`;
  }
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}
