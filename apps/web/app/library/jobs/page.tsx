"use client";

import { ChevronDown } from "lucide-react";
import { useState } from "react";
import { AddTargetJobDialog } from "@/components/library/AddTargetJobDialog";
import { DeleteConfirm } from "@/components/library/DeleteConfirm";
import { InlineRename } from "@/components/library/InlineRename";
import { EmptyState, ErrorState, ListSkeleton } from "@/components/library/ListStates";
import { PageHeader } from "@/components/shell/AppShell";
import { Button } from "@/components/ui/button";
import type { TargetJob } from "@/lib/api/types";
import { useTargetJob, useTargetJobs } from "@/lib/query/library";

function TargetJobRow({ job }: { job: TargetJob }) {
  const [expanded, setExpanded] = useState(false);
  const detail = useTargetJob(job.id, { enabled: expanded });
  return (
    <li id={`item-${job.id}`} className="scroll-mt-20 rounded-lg border bg-card target:ring-2 target:ring-primary">
      <div className="flex flex-wrap items-center gap-3 px-4 py-3">
        <div className="min-w-0 flex-1">
          <p className="truncate font-medium">{job.name}</p>
          <p className="text-xs text-muted-foreground">Saved {new Date(job.createdAt).toLocaleDateString()}</p>
        </div>
        <InlineRename kind="target-jobs" id={job.id} name={job.name} max={80} />
        <DeleteConfirm kind="target-jobs" id={job.id} name={job.name} />
        <Button
          variant="ghost"
          size="icon"
          aria-expanded={expanded}
          aria-label={expanded ? "Hide description" : "Show description"}
          onClick={() => setExpanded((value) => !value)}
        >
          <ChevronDown className={expanded ? "rotate-180 transition-transform" : "transition-transform"} />
        </Button>
      </div>
      {expanded ? (
        <pre className="max-h-80 overflow-auto whitespace-pre-wrap border-t px-4 py-3 font-sans text-sm text-muted-foreground">
          {detail.data?.text ?? "Loading…"}
        </pre>
      ) : null}
    </li>
  );
}

export default function TargetJobsPage() {
  const jobs = useTargetJobs();
  const add = <AddTargetJobDialog />;
  return (
    <>
      <PageHeader title="Target jobs" description="Job descriptions you want to tailor for and practice against." action={add} />
      {jobs.isPending ? <ListSkeleton /> : null}
      {jobs.isError ? <ErrorState error={jobs.error} onRetry={() => jobs.refetch()} /> : null}
      {jobs.data?.items.length === 0 ? (
        <EmptyState title="No target jobs yet" description="Paste a job description to see your fit and practice for it." action={add} />
      ) : null}
      {jobs.data?.items.length ? (
        <ul className="space-y-3">{jobs.data.items.map((job) => <TargetJobRow key={job.id} job={job} />)}</ul>
      ) : null}
    </>
  );
}
