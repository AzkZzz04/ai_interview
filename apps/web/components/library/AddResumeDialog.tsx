"use client";

import { Plus } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import type { Resume } from "@/lib/api/types";
import { friendlyError } from "@/lib/errorMessages";
import { usePasteResume, useUploadResume } from "@/lib/query/library";
import { cn } from "@/lib/utils";
import { DuplicateNotice } from "./DuplicateNotice";
import { TextField } from "./TextField";

const firstLine = (text: string) => text.trim().split("\n")[0]?.trim().slice(0, 80) ?? "";

/** Upload a file or paste text. Calls `onAdded` for a new resume; duplicates are shown inside the dialog. */
export function AddResumeDialog({ onAdded, trigger }: { onAdded?: (resume: Resume) => void; trigger?: React.ReactNode }) {
  const [open, setOpen] = useState(false);
  const [mode, setMode] = useState<"upload" | "paste">("upload");
  const [name, setName] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [text, setText] = useState("");
  const [submitted, setSubmitted] = useState(false);
  const [duplicate, setDuplicate] = useState<Resume | null>(null);
  const upload = useUploadResume();
  const paste = usePasteResume();
  const mutation = mode === "upload" ? upload : paste;

  const nameError = submitted && !name.trim() ? "Give this resume a name." : null;
  const fileError = submitted && mode === "upload" && !file ? "Choose a file to upload." : null;
  const textLength = text.trim().length;
  const textError = submitted && mode === "paste" && textLength < 100 ? "Paste at least 100 characters." : null;

  function reset() {
    setName("");
    setFile(null);
    setText("");
    setSubmitted(false);
    setDuplicate(null);
    upload.reset();
    paste.reset();
  }

  function submit(event: React.FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    setDuplicate(null);
    if (!name.trim() || (mode === "upload" ? !file : textLength < 100 || textLength > 50_000)) return;
    const onSuccess = ({ resume, duplicate: isDuplicate }: { resume: Resume; duplicate: boolean }) => {
      if (isDuplicate) {
        setDuplicate(resume);
        return;
      }
      onAdded?.(resume);
      setOpen(false);
      reset();
    };
    if (mode === "upload") upload.mutate({ file: file!, name: name.trim() }, { onSuccess });
    else paste.mutate({ name: name.trim(), jobTitle: null, text }, { onSuccess });
  }

  return (
    <Dialog open={open} onOpenChange={(next) => { setOpen(next); if (!next) reset(); }}>
      <DialogTrigger asChild>
        {trigger ?? <Button><Plus /> Add resume</Button>}
      </DialogTrigger>
      <DialogContent className="sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>Add a resume</DialogTitle>
          <DialogDescription>Upload a file or paste the text. Give it a name you will recognize later.</DialogDescription>
        </DialogHeader>
        <div role="tablist" aria-label="How to add" className="inline-flex rounded-md border p-1">
          {(["upload", "paste"] as const).map((option) => (
            <button
              key={option}
              type="button"
              role="tab"
              aria-selected={mode === option}
              onClick={() => { setMode(option); setSubmitted(false); setDuplicate(null); }}
              className={cn("rounded px-3 py-1 text-sm", mode === option ? "bg-accent font-medium" : "text-muted-foreground")}
            >
              {option === "upload" ? "Upload file" : "Paste text"}
            </button>
          ))}
        </div>
        <form className="space-y-4" onSubmit={submit} noValidate>
          {mode === "upload" ? (
            <div className="space-y-2">
              <Label htmlFor="resume-file">File</Label>
              <Input
                id="resume-file"
                type="file"
                accept=".pdf,.doc,.docx,.txt,.md,.markdown"
                aria-invalid={Boolean(fileError)}
                onChange={(event) => {
                  const chosen = event.target.files?.[0] ?? null;
                  setFile(chosen);
                  if (chosen && !name.trim()) setName(chosen.name.replace(/\.[^.]+$/, "").slice(0, 80));
                }}
              />
              <p className="text-xs text-muted-foreground">PDF, DOC, DOCX, TXT or Markdown, up to 10 MB.</p>
              {fileError ? <p className="text-sm text-destructive">{fileError}</p> : null}
            </div>
          ) : (
            <TextField
              label="Resume text"
              multiline
              value={text}
              max={50_000}
              error={textError}
              onChange={(value) => {
                if (!name.trim() && !text) setName(firstLine(value));
                setText(value);
              }}
            />
          )}
          <TextField label="Name" value={name} onChange={setName} max={80} error={nameError} placeholder="For example: Backend 2026" />
          {duplicate ? <DuplicateNotice kind="resumes" id={duplicate.id} name={duplicate.name} max={80} /> : null}
          {mutation.isError ? <p role="alert" className="text-sm text-destructive">{friendlyError(mutation.error)}</p> : null}
          <div className="flex justify-end gap-2">
            <Button type="button" variant="ghost" onClick={() => setOpen(false)}>Cancel</Button>
            <Button type="submit" disabled={mutation.isPending}>{mutation.isPending ? "Saving…" : "Save resume"}</Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
