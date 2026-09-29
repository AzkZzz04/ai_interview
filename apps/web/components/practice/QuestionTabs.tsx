"use client";

import { useRef, type KeyboardEvent } from "react";
import type { Question } from "@/lib/api/types";
import { cn } from "@/lib/utils";

const latestScore = (question: Question) =>
  [...question.attempts].reverse().find((attempt) => attempt.feedback)?.feedback?.score ?? null;

/** Vertical tab list on desktop, a scrolling strip of numbers on narrow screens. Arrow keys move between tabs. */
export function QuestionTabs({ questions, selectedId, onSelect }: {
  questions: Question[];
  selectedId: string;
  onSelect: (id: string) => void;
}) {
  const refs = useRef<(HTMLButtonElement | null)[]>([]);
  function onKeyDown(event: KeyboardEvent, index: number) {
    const step = ["ArrowDown", "ArrowRight"].includes(event.key) ? 1 : ["ArrowUp", "ArrowLeft"].includes(event.key) ? -1 : 0;
    if (!step) return;
    event.preventDefault();
    const next = (index + step + questions.length) % questions.length;
    onSelect(questions[next].id);
    refs.current[next]?.focus();
  }
  return (
    <div role="tablist" aria-label="Questions" aria-orientation="vertical" className="flex gap-2 overflow-x-auto pb-2 md:flex-col md:overflow-visible md:pb-0">
      {questions.map((question, index) => {
        const selected = question.id === selectedId;
        const score = latestScore(question);
        const pending = question.attempts.at(-1)?.status === "PENDING";
        return (
          <button
            key={question.id}
            ref={(element) => { refs.current[index] = element; }}
            type="button"
            role="tab"
            id={`tab-${question.id}`}
            aria-selected={selected}
            aria-controls="question-panel"
            tabIndex={selected ? 0 : -1}
            onClick={() => onSelect(question.id)}
            onKeyDown={(event) => onKeyDown(event, index)}
            className={cn(
              "flex shrink-0 items-center gap-3 rounded-lg border px-3 py-2 text-left text-sm transition-colors md:w-full",
              selected ? "border-primary bg-accent" : "hover:bg-accent/50"
            )}
          >
            <span className="font-medium">{index + 1}</span>
            <span className="hidden min-w-0 flex-1 truncate md:block">{question.text}</span>
            <span className="text-xs text-muted-foreground">{pending ? "…" : score ?? "–"}</span>
          </button>
        );
      })}
    </div>
  );
}
