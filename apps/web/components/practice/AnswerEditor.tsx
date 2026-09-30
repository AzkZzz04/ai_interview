import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import type { Question } from "@/lib/api/types";
import { readDraft, writeDraft } from "@/lib/drafts";
import { friendlyError } from "@/lib/errorMessages";
import { useSubmitAttempt } from "@/lib/query/practice";
import { cn } from "@/lib/utils";

const MAX = 4_000;

/** Mount with key={question.id}: the draft is read once per question and saved on every change. */
export function AnswerEditor({ setId, question }: { setId: string; question: Question }) {
  const [text, setText] = useState(() => readDraft(question.id));
  const submit = useSubmitAttempt(setId);
  const latest = question.attempts.at(-1);
  const pending = latest?.status === "PENDING";
  const trimmed = text.trim();
  const over = text.length > MAX;
  const unchanged = Boolean(latest) && trimmed === latest!.text;
  const reason = pending ? "Scoring in progress" : !trimmed ? null : over ? `Keep your answer under ${MAX.toLocaleString()} characters.` : unchanged ? "This is the same as your last attempt." : null;

  return (
    <form
      className="space-y-2"
      onSubmit={(event) => {
        event.preventDefault();
        if (reason || !trimmed) return;
        submit.mutate({ questionId: question.id, text: trimmed }, {
          onSuccess: () => {
            writeDraft(question.id, "");
            setText("");
          }
        });
      }}
    >
      <div className="flex items-baseline justify-between">
        <Label htmlFor={`answer-${question.id}`}>Your answer</Label>
        <span id={`count-${question.id}`} className={cn("text-xs text-muted-foreground", over && "text-destructive")}>
          {text.length.toLocaleString()} / {MAX.toLocaleString()}
        </span>
      </div>
      <Textarea
        id={`answer-${question.id}`}
        rows={8}
        value={text}
        aria-invalid={over}
        aria-describedby={`count-${question.id}`}
        placeholder="Answer as you would in the interview. Situation, what you did, the result."
        onChange={(event) => {
          setText(event.target.value);
          writeDraft(question.id, event.target.value);
        }}
      />
      <div className="flex flex-wrap items-center gap-3">
        <Button type="submit" disabled={Boolean(reason) || !trimmed || submit.isPending}>
          {latest ? "Submit new attempt" : "Submit answer"}
        </Button>
        {reason ? <span className={cn("text-sm", over ? "text-destructive" : "text-muted-foreground")} role="status">{reason}</span> : null}
        {submit.isError ? <span role="alert" className="text-sm text-destructive">{friendlyError(submit.error)}</span> : null}
      </div>
    </form>
  );
}
