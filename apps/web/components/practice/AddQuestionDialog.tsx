import { Plus } from "lucide-react";
import { useState } from "react";
import { TextField } from "@/components/library/TextField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { friendlyError } from "@/lib/errorMessages";
import { useAddQuestion } from "@/lib/query/practice";

export const USER_QUESTION_LIMIT = 10;

export function AddQuestionDialog({ setId, userQuestions, onAdded }: { setId: string; userQuestions: number; onAdded: (id: string) => void }) {
  const [open, setOpen] = useState(false);
  const [text, setText] = useState("");
  const [submitted, setSubmitted] = useState(false);
  const add = useAddQuestion(setId);
  const full = userQuestions >= USER_QUESTION_LIMIT;
  const length = text.trim().length;
  const error = submitted && length < 10 ? "Questions need at least 10 characters." : null;

  if (full) {
    return (
      <div className="space-y-1">
        <Button variant="outline" size="sm" disabled><Plus /> Add your own question</Button>
        <p className="text-xs text-muted-foreground">You have added the maximum of {USER_QUESTION_LIMIT} questions.</p>
      </div>
    );
  }

  return (
    <Dialog open={open} onOpenChange={(next) => { setOpen(next); if (!next) { setText(""); setSubmitted(false); add.reset(); } }}>
      <DialogTrigger asChild><Button variant="outline" size="sm"><Plus /> Add your own question</Button></DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Add your own question</DialogTitle>
          <DialogDescription>A question you expect for this job. It gets the same feedback as the others.</DialogDescription>
        </DialogHeader>
        <form
          className="space-y-4"
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            setSubmitted(true);
            if (length < 10 || length > 500) return;
            add.mutate(text.trim(), {
              onSuccess: (question) => {
                onAdded(question.id);
                setOpen(false);
                setText("");
                setSubmitted(false);
              }
            });
          }}
        >
          <TextField label="Question" multiline rows={3} value={text} onChange={setText} max={500} error={error} />
          {add.isError ? <p role="alert" className="text-sm text-destructive">{friendlyError(add.error)}</p> : null}
          <div className="flex justify-end gap-2">
            <Button type="button" variant="ghost" onClick={() => setOpen(false)}>Cancel</Button>
            <Button type="submit" disabled={add.isPending}>Add question</Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
