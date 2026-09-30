import { Copy } from "lucide-react";
import { useRef } from "react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import type { ResumeScoreResult } from "@/lib/api/types";

const SUB_SCORES: [keyof ResumeScoreResult["scores"], string][] = [
  ["technicalDepth", "Technical depth"],
  ["impact", "Impact"],
  ["clarity", "Clarity"],
  ["relevance", "Relevance"],
  ["ats", "ATS readability"]
];

export function ScoreBar({ label, value }: { label: string; value: number }) {
  return (
    <div className="space-y-1">
      <div className="flex justify-between text-sm"><span>{label}</span><span className="font-medium">{value}</span></div>
      <div className="h-2 rounded-full bg-muted" role="presentation">
        <div className="h-2 rounded-full bg-primary" style={{ width: `${value}%` }} />
      </div>
    </div>
  );
}

export const PRIORITY_VARIANT = { HIGH: "destructive", MEDIUM: "default", LOW: "secondary" } as const;

function Rewrite({ original, rewritten }: { original: string; rewritten: string }) {
  const textRef = useRef<HTMLParagraphElement>(null);
  async function copy() {
    try {
      await navigator.clipboard.writeText(rewritten);
      toast.success("Copied. Replace the placeholders with your real numbers.");
    }
    catch {
      // Clipboard can be blocked; select the text so the user can copy it by hand.
      const range = document.createRange();
      range.selectNodeContents(textRef.current!);
      window.getSelection()?.removeAllRanges();
      window.getSelection()?.addRange(range);
      toast("Text selected. Press Ctrl+C or Cmd+C to copy.");
    }
  }
  return (
    <li className="space-y-2 rounded-lg border p-4">
      <p className="text-sm text-muted-foreground line-through decoration-muted-foreground/40">{original}</p>
      <div className="flex items-start gap-3">
        <p ref={textRef} className="flex-1 text-sm">{rewritten}</p>
        <Button size="sm" variant="outline" onClick={copy} aria-label="Copy rewrite"><Copy /> Copy</Button>
      </div>
    </li>
  );
}

export function ScoreCard({ score, stale }: { score: ResumeScoreResult; stale?: boolean }) {
  return (
    <div className="space-y-8">
      <section aria-labelledby="overall" className="flex flex-wrap items-center gap-6 rounded-xl border bg-card p-6">
        <div>
          <p id="overall" className="text-sm text-muted-foreground">Overall score</p>
          <p className="text-5xl font-semibold tracking-tight">{score.overall}<span className="text-lg text-muted-foreground">/100</span></p>
        </div>
        <p className="min-w-60 flex-1 text-muted-foreground">{score.summary}</p>
        {stale ? <Badge variant="outline">Out of date</Badge> : null}
      </section>
      <section aria-label="Sub-scores" className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {SUB_SCORES.map(([key, label]) => <ScoreBar key={key} label={label} value={score.scores[key]} />)}
      </section>
      <section className="space-y-3">
        <h2 className="text-lg font-semibold">Top fixes</h2>
        <ol className="space-y-2">
          {score.fixes.map((fix) => (
            <li key={fix.rank} className="flex gap-3 rounded-lg border p-4 text-sm">
              <span className="font-semibold text-muted-foreground">{fix.rank}.</span>
              <div className="flex-1 space-y-1">
                <div className="flex items-center gap-2">
                  <span className="font-medium">{fix.section}</span>
                  <Badge variant={PRIORITY_VARIANT[fix.priority]}>{fix.priority.toLowerCase()}</Badge>
                </div>
                <p>{fix.message}</p>
              </div>
            </li>
          ))}
        </ol>
      </section>
      {score.rewrites.length ? (
        <section className="space-y-3">
          <h2 className="text-lg font-semibold">Suggested rewrites</h2>
          <p className="text-sm text-muted-foreground">Bracketed parts such as [X%] are placeholders for facts only you know.</p>
          <ul className="space-y-2">{score.rewrites.map((rewrite, index) => <Rewrite key={index} {...rewrite} />)}</ul>
        </section>
      ) : null}
    </div>
  );
}
