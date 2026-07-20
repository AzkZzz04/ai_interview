"use client";

import { BarChart3 } from "lucide-react";
import type { EvidenceChunk } from "@/lib/evidence";
import { Assessment, AssessmentScoreKey } from "@/lib/mockAssessment";
import { EmptyState } from "./EmptyState";
import { EvidenceRefs } from "./EvidenceRefs";
import { InsightList } from "./InsightList";

const scoreLabels: Record<AssessmentScoreKey, string> = {
  technicalDepth: "Technical depth",
  impact: "Impact",
  clarity: "Clarity",
  relevance: "Role relevance",
  ats: "ATS"
};

export function AssessmentPanel({
  targetRole,
  seniority,
  assessment,
  evidenceResumeText,
  evidenceJobDescription,
  evidenceResumeChunks
}: {
  targetRole: string;
  seniority: string;
  assessment: Assessment | null;
  evidenceResumeText?: string;
  evidenceJobDescription?: string;
  evidenceResumeChunks?: EvidenceChunk[];
}) {
  return (
    <section className="panel" id="assessment">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">{targetRole} · {seniority}</p>
          <h2>Assessment</h2>
        </div>
        <div className="score-ring" aria-label={`Overall score ${assessment?.overallScore ?? 0}`}>
          {assessment?.overallScore ?? "--"}
        </div>
      </div>

      {assessment ? (
        <>
          <div className="score-grid">
            {(Object.keys(assessment.scores) as AssessmentScoreKey[]).map((key) => (
              <div className="metric" key={key}>
                <div>
                  <span>{scoreLabels[key]}</span>
                  <strong>{assessment.scores[key]}</strong>
                </div>
                <div className="meter">
                  <span style={{ width: `${assessment.scores[key]}%` }} />
                </div>
              </div>
            ))}
          </div>

          <div className="insight-columns">
            <InsightList title="Strengths" items={assessment.strengths} tone="positive" />
            <InsightList title="Gaps" items={assessment.weaknesses} tone="warning" />
          </div>

          <div className="recommendation-list">
            {assessment.recommendations.map((recommendation) => (
              <article className="recommendation" key={recommendation.message}>
                <span className={`priority ${recommendation.priority}`}>{recommendation.priority}</span>
                <div>
                  <strong>{recommendation.section}</strong>
                  <p>{recommendation.message}</p>
                </div>
              </article>
            ))}
          </div>

          <EvidenceRefs
            ids={assessment.sourceContextIds}
            resumeText={evidenceResumeText}
            jobDescription={evidenceJobDescription}
            resumeChunks={evidenceResumeChunks}
          />
        </>
      ) : (
        <EmptyState
          icon={<BarChart3 size={24} aria-hidden="true" />}
          title="No assessment yet"
          text="Run the analysis to score the resume and prepare interview questions."
        />
      )}
    </section>
  );
}
