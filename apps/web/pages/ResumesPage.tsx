import { useState } from "react";
import { AddResumeDialog } from "@/components/library/AddResumeDialog";
import { EmptyState, ErrorState, ListSkeleton } from "@/components/library/ListStates";
import { ResumeRow } from "@/components/library/ResumeRow";
import { UploadWatcher } from "@/components/library/UploadWatcher";
import { PageHeader } from "@/components/shell/AppShell";
import { useResumes } from "@/lib/query/library";

export default function ResumesPage() {
  const resumes = useResumes();
  // Upload jobs to watch: an upload whose text matches a saved resume turns into a duplicate notice.
  const [uploadJobs, setUploadJobs] = useState<string[]>([]);
  const add = (
    <AddResumeDialog onAdded={(resume) => resume.activeJob && setUploadJobs((jobs) => [...jobs, resume.activeJob!.jobId])} />
  );

  return (
    <>
      <PageHeader title="Resumes" description="Named resumes you can score and tailor." action={add} />
      <div className="space-y-3">
        {uploadJobs.map((jobId) => (
          <UploadWatcher key={jobId} jobId={jobId} onDismiss={() => setUploadJobs((jobs) => jobs.filter((id) => id !== jobId))} />
        ))}
        {resumes.isPending ? <ListSkeleton /> : null}
        {resumes.isError ? <ErrorState error={resumes.error} onRetry={() => resumes.refetch()} /> : null}
        {resumes.data?.items.length === 0 ? (
          <EmptyState title="No resumes yet" description="Upload a file or paste your resume text to get a score." action={add} />
        ) : null}
        {resumes.data?.items.length ? (
          <ul className="space-y-3">{resumes.data.items.map((resume) => <ResumeRow key={resume.id} resume={resume} />)}</ul>
        ) : null}
      </div>
    </>
  );
}
