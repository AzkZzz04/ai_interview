"use client";

import { useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { friendlyError } from "@/lib/errorMessages";
import { useRename, type LibraryKind } from "@/lib/query/library";

/** One rename control shared by library rows and duplicate notices. */
export function InlineRename({ kind, id, name, max }: { kind: LibraryKind; id: string; name: string; max: number }) {
  const [draft, setDraft] = useState<string | null>(null);
  const rename = useRename(kind);

  if (draft === null) {
    return (
      <Button variant="ghost" size="sm" onClick={() => setDraft(name)} aria-label={`Rename ${name}`}>
        Rename
      </Button>
    );
  }

  const value = draft.trim();
  const error = value.length === 0 ? "Enter a name." : value.length > max ? `Keep it under ${max} characters.` : null;
  return (
    <form
      className="flex flex-wrap items-center gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        if (error) return;
        rename.mutate({ id, name: value }, {
          onSuccess: () => setDraft(null),
          onError: (failure) => toast.error(friendlyError(failure))
        });
      }}
    >
      <Input
        aria-label="New name"
        value={draft}
        autoFocus
        className="h-8 w-56"
        aria-invalid={Boolean(error)}
        onChange={(event) => setDraft(event.target.value)}
        onKeyDown={(event) => event.key === "Escape" && setDraft(null)}
      />
      <Button type="submit" size="sm" disabled={Boolean(error) || rename.isPending}>Save</Button>
      <Button type="button" size="sm" variant="ghost" onClick={() => setDraft(null)}>Cancel</Button>
      {error ? <span className="text-xs text-destructive">{error}</span> : null}
    </form>
  );
}
