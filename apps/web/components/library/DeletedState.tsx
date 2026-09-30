import { Link } from "@tanstack/react-router";
import { Button } from "@/components/ui/button";

/** Shown for links to items that were deleted or never existed. */
export function DeletedState({ what = "item" }: { what?: string }) {
  return (
    <div className="mx-auto flex max-w-md flex-col items-center gap-4 py-16 text-center">
      <h1 className="text-xl font-semibold">This {what} was deleted</h1>
      <p className="text-muted-foreground">It is no longer in your library. Pick another one to continue.</p>
      <div className="flex gap-2">
        <Button asChild><Link to="/library/resumes">Open library</Link></Button>
        <Button asChild variant="outline"><Link to="/">Home</Link></Button>
      </div>
    </div>
  );
}
