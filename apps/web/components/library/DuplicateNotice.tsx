"use client";

import { Info, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import type { LibraryKind } from "@/lib/query/library";
import { InlineRename } from "./InlineRename";

/** "Already saved as <name>": links to the existing row (highlighted via :target) and offers Rename. */
export function DuplicateNotice({ kind, id, name, max, onDismiss }: {
  kind: LibraryKind;
  id: string;
  name: string;
  max: number;
  onDismiss?: () => void;
}) {
  return (
    <div role="status" className="flex flex-wrap items-center gap-2 rounded-lg border border-primary/30 bg-accent px-4 py-3 text-sm">
      <Info className="size-4 text-primary" aria-hidden />
      <span>
        Already saved as <a href={`#item-${id}`} className="font-medium underline underline-offset-4">{name}</a>
      </span>
      <InlineRename kind={kind} id={id} name={name} max={max} />
      {onDismiss ? (
        <Button variant="ghost" size="icon" className="ml-auto size-7" onClick={onDismiss} aria-label="Dismiss">
          <X />
        </Button>
      ) : null}
    </div>
  );
}
