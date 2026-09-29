import { useId } from "react";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { cn } from "@/lib/utils";

type Props = {
  label: string;
  value: string;
  onChange: (value: string) => void;
  error?: string | null;
  /** Shows a live counter and an inline error past this length. */
  max?: number;
  multiline?: boolean;
  optional?: boolean;
  placeholder?: string;
  rows?: number;
};

export function TextField({ label, value, onChange, error, max, multiline, optional, placeholder, rows = 8 }: Props) {
  const id = useId();
  const over = max !== undefined && value.length > max;
  const message = error ?? (over ? `Keep this under ${max!.toLocaleString()} characters.` : null);
  const describedBy = [message ? `${id}-error` : null, max ? `${id}-count` : null].filter(Boolean).join(" ") || undefined;
  const Control = multiline ? Textarea : Input;
  return (
    <div className="space-y-2">
      <div className="flex items-baseline justify-between gap-2">
        <Label htmlFor={id}>
          {label}
          {optional ? <span className="font-normal text-muted-foreground"> (optional)</span> : null}
        </Label>
        {max ? (
          <span id={`${id}-count`} className={cn("text-xs text-muted-foreground", over && "text-destructive")}>
            {value.length.toLocaleString()} / {max.toLocaleString()}
          </span>
        ) : null}
      </div>
      <Control
        id={id}
        value={value}
        placeholder={placeholder}
        rows={multiline ? rows : undefined}
        aria-invalid={Boolean(message)}
        aria-describedby={describedBy}
        onChange={(event) => onChange(event.target.value)}
        className={multiline ? "max-h-80" : undefined}
      />
      {message ? <p id={`${id}-error`} className="text-sm text-destructive">{message}</p> : null}
    </div>
  );
}
