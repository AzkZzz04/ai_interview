import { Badge } from "@/components/ui/badge";
import type { Attempt } from "@/lib/api/types";

export function Delta({ value }: { value: number | null }) {
  if (value === null) return null;
  const label = value > 0 ? `+${value}` : String(value);
  return (
    <Badge variant={value > 0 ? "default" : value < 0 ? "destructive" : "secondary"} aria-label={`Change from previous: ${label}`}>
      {label}
    </Badge>
  );
}

function List({ title, items }: { title: string; items: string[] }) {
  if (items.length === 0) return null;
  return (
    <div className="space-y-1">
      <h4 className="text-sm font-medium">{title}</h4>
      <ul className="list-disc space-y-1 pl-5 text-sm text-muted-foreground">{items.map((item) => <li key={item}>{item}</li>)}</ul>
    </div>
  );
}

export function Feedback({ attempt }: { attempt: Attempt }) {
  const feedback = attempt.feedback!;
  return (
    <div className="space-y-4 rounded-xl border bg-card p-5">
      <div className="flex flex-wrap items-center gap-3">
        <p className="text-3xl font-semibold">{feedback.score}<span className="text-base text-muted-foreground">/100</span></p>
        <Delta value={attempt.scoreDelta} />
        <span className="text-sm text-muted-foreground">Attempt {attempt.number}</span>
      </div>
      <p>{feedback.summary}</p>
      {feedback.nextStep ? <p className="text-sm"><span className="font-medium">Next step: </span>{feedback.nextStep}</p> : null}
      <div className="grid gap-4 md:grid-cols-2">
        <List title="Strengths" items={feedback.strengths} />
        <List title="Gaps" items={feedback.gaps} />
      </div>
      <List title="A stronger answer covers" items={feedback.betterAnswerOutline} />
      {feedback.followUpQuestion ? (
        <p className="text-sm text-muted-foreground"><span className="font-medium text-foreground">Likely follow-up: </span>{feedback.followUpQuestion}</p>
      ) : null}
    </div>
  );
}
