"use client";

import { ChevronRight, Loader2 } from "lucide-react";
import { useRouter } from "next/navigation";
import { Stepper } from "@/components/flow/Stepper";
import { StepHeading } from "@/components/flow/StepHeading";
import { AddResumeDialog } from "@/components/library/AddResumeDialog";
import { EmptyState, ErrorState, ListSkeleton } from "@/components/library/ListStates";
import { Badge } from "@/components/ui/badge";
import { saveLastPair } from "@/lib/lastPair";
import { useResumes } from "@/lib/query/library";

export default function ResumePickerPage() {
  const router = useRouter();
  const resumes = useResumes();
  const choose = (id: string) => {
    saveLastPair({ resumeId: id, targetJobId: null });
    router.push(`/flow/${id}`);
  };
  const add = <AddResumeDialog onAdded={(resume) => resume.status === "READY" && choose(resume.id)} />;

  return (
    <>
      <Stepper current={1} />
      <StepHeading title="Choose a resume" description="Pick the resume to score and tailor, or add a new one." action={add} />
      {resumes.isPending ? <ListSkeleton /> : null}
      {resumes.isError ? <ErrorState error={resumes.error} onRetry={() => resumes.refetch()} /> : null}
      {resumes.data?.items.length === 0 ? (
        <EmptyState title="No resumes yet" description="Upload a file or paste your resume text to begin." action={add} />
      ) : null}
      <ul className="space-y-2">
        {resumes.data?.items.map((resume) => {
          const ready = resume.status === "READY";
          return (
            <li key={resume.id}>
              <button
                type="button"
                disabled={!ready}
                onClick={() => choose(resume.id)}
                className="flex w-full items-center gap-3 rounded-lg border bg-card px-4 py-3 text-left transition-colors hover:border-primary/50 hover:bg-accent/40 disabled:cursor-not-allowed disabled:opacity-60"
              >
                <span className="min-w-0 flex-1">
                  <span className="block truncate font-medium">{resume.name}</span>
                  <span className="block text-xs text-muted-foreground">
                    {resume.status === "PROCESSING" ? (
                      <span className="inline-flex items-center gap-1"><Loader2 className="size-3 animate-spin" aria-hidden /> Processing, available shortly</span>
                    ) : resume.status === "FAILED" ? "Could not be read. Delete it from the library and try another file." : resume.jobTitle ?? "No job title"}
                  </span>
                </span>
                {resume.latestScore ? <Badge variant="secondary">Score {resume.latestScore.overall}</Badge> : null}
                {ready ? <ChevronRight className="size-4 text-muted-foreground" aria-hidden /> : null}
              </button>
            </li>
          );
        })}
      </ul>
    </>
  );
}
