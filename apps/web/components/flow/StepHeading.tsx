import { useEffect, useRef, type ReactNode } from "react";

/** Page heading that takes focus after navigation, so screen readers announce the new step. */
export function StepHeading({ title, description, action }: { title: string; description?: ReactNode; action?: ReactNode }) {
  const ref = useRef<HTMLHeadingElement>(null);
  useEffect(() => ref.current?.focus(), []);
  return (
    <div className="mb-8 flex flex-wrap items-start justify-between gap-4">
      <div className="space-y-1">
        <h1 ref={ref} tabIndex={-1} className="text-2xl font-semibold tracking-tight outline-none">{title}</h1>
        {description ? <p className="text-muted-foreground">{description}</p> : null}
      </div>
      {action}
    </div>
  );
}
