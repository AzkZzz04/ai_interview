"use client";

import { Plus } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import type { TargetJobDetail } from "@/lib/api/types";
import { friendlyError } from "@/lib/errorMessages";
import { useCreateTargetJob } from "@/lib/query/library";
import { DuplicateNotice } from "./DuplicateNotice";
import { TextField } from "./TextField";

const MAX = 20_000;

/** Paste a job description and save it as a named, reusable target job. */
export function AddTargetJobDialog({ onAdded, trigger }: { onAdded?: (job: TargetJobDetail) => void; trigger?: React.ReactNode }) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [text, setText] = useState("");
  const [submitted, setSubmitted] = useState(false);
  const [duplicate, setDuplicate] = useState<TargetJobDetail | null>(null);
  const create = useCreateTargetJob();

  const nameError = submitted && !name.trim() ? "Give this job a name." : null;
  const textError = submitted && text.trim().length < 100 ? "Paste at least 100 characters of the job description." : null;

  function reset() {
    setName("");
    setText("");
    setSubmitted(false);
    setDuplicate(null);
    create.reset();
  }

  return (
    <Dialog open={open} onOpenChange={(next) => { setOpen(next); if (!next) reset(); }}>
      <DialogTrigger asChild>{trigger ?? <Button><Plus /> Add target job</Button>}</DialogTrigger>
      <DialogContent className="sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>Add a target job</DialogTitle>
          <DialogDescription>Paste the job description. The saved text cannot be edited later, only renamed.</DialogDescription>
        </DialogHeader>
        <form
          className="space-y-4"
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            setSubmitted(true);
            setDuplicate(null);
            const length = text.trim().length;
            if (!name.trim() || length < 100 || length > MAX) return;
            create.mutate({ name: name.trim(), text }, {
              onSuccess: ({ targetJob, duplicate: isDuplicate }) => {
                if (isDuplicate) return setDuplicate(targetJob);
                onAdded?.(targetJob);
                setOpen(false);
                reset();
              }
            });
          }}
        >
          <TextField label="Job description" multiline value={text} onChange={setText} max={MAX} error={textError} />
          <TextField label="Name" value={name} onChange={setName} max={80} error={nameError} placeholder="For example: Acme — Senior Backend" />
          {duplicate ? <DuplicateNotice kind="target-jobs" id={duplicate.id} name={duplicate.name} max={80} /> : null}
          {create.isError ? <p role="alert" className="text-sm text-destructive">{friendlyError(create.error)}</p> : null}
          <div className="flex justify-end gap-2">
            <Button type="button" variant="ghost" onClick={() => setOpen(false)}>Cancel</Button>
            <Button type="submit" disabled={create.isPending}>{create.isPending ? "Saving…" : "Save job"}</Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
