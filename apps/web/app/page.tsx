"use client";

import { ArrowRight } from "lucide-react";
import Link from "next/link";
import { useSyncExternalStore } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { readLastPair } from "@/lib/lastPair";
import { useResumes, useTargetJobs } from "@/lib/query/library";

const noSubscribe = () => () => {};

function ContinueCard() {
  const stored = useSyncExternalStore(noSubscribe, () => JSON.stringify(readLastPair()), () => "null");
  const pair = JSON.parse(stored) as ReturnType<typeof readLastPair>;
  const resumes = useResumes();
  const jobs = useTargetJobs();
  // Offer Continue only when the stored items still exist.
  const resume = resumes.data?.items.find((item) => item.id === pair?.resumeId);
  const job = jobs.data?.items.find((item) => item.id === pair?.targetJobId);
  if (!pair || !resume) return null;

  const href = job ? `/flow/${resume.id}/jobs/${job.id}` : `/flow/${resume.id}`;
  return (
    <Card>
      <CardHeader>
        <CardTitle>Pick up where you left off</CardTitle>
        <CardDescription>{job ? `${resume.name} × ${job.name}` : resume.name}</CardDescription>
      </CardHeader>
      <CardContent>
        <Button asChild>
          <Link href={href as never}>Continue <ArrowRight /></Link>
        </Button>
      </CardContent>
    </Card>
  );
}

export default function HomePage() {
  return (
    <div className="mx-auto max-w-3xl space-y-10">
      <div className="space-y-3">
        <h1 className="text-3xl font-semibold tracking-tight">Get your resume ready, then practice</h1>
        <p className="text-lg text-muted-foreground">
          Score your resume, check how it fits a job, find past work worth adding, and practice the interview questions that job is likely to bring.
        </p>
      </div>
      <ContinueCard />
      <Card>
        <CardHeader>
          <CardTitle>Start with a resume</CardTitle>
          <CardDescription>Choose a saved resume or add a new one.</CardDescription>
        </CardHeader>
        <CardContent>
          <Button asChild variant="outline">
            <Link href="/flow">Start <ArrowRight /></Link>
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}
