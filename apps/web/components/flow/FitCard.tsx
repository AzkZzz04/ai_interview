import { Badge } from "@/components/ui/badge";
import type { JobFitResult } from "@/lib/api/types";
import { PRIORITY_VARIANT } from "./ScoreCard";

export function FitCard({ fit }: { fit: JobFitResult }) {
  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center gap-6 rounded-xl border bg-card p-6">
        <div>
          <p className="text-sm text-muted-foreground">Job fit</p>
          <p className="text-5xl font-semibold tracking-tight">{fit.fitScore}<span className="text-lg text-muted-foreground">/100</span></p>
        </div>
        <p className="min-w-60 flex-1 text-muted-foreground">{fit.summary}</p>
      </div>
      <div className="grid gap-6 md:grid-cols-2">
        <section className="space-y-2">
          <h3 className="font-semibold">Missing</h3>
          <ul className="space-y-2">
            {fit.missingRequirements.map((item) => (
              <li key={item.requirement} className="rounded-lg border p-3 text-sm">
                <p className="font-medium">{item.requirement}</p>
                <p className="text-muted-foreground">{item.guidance}</p>
              </li>
            ))}
          </ul>
        </section>
        <section className="space-y-2">
          <h3 className="font-semibold">Matched</h3>
          <ul className="space-y-2">
            {fit.matchedRequirements.map((item) => (
              <li key={item.requirement} className="rounded-lg border p-3 text-sm">
                <p className="font-medium">{item.requirement}</p>
                <p className="text-muted-foreground">{item.evidence}</p>
              </li>
            ))}
          </ul>
        </section>
      </div>
      <section className="space-y-2">
        <h3 className="font-semibold">Feedback for this job</h3>
        <ul className="space-y-2">
          {fit.feedback.map((item) => (
            <li key={item.message} className="flex items-start gap-2 text-sm">
              <Badge variant={PRIORITY_VARIANT[item.priority]}>{item.priority.toLowerCase()}</Badge>
              <span>{item.message}</span>
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
