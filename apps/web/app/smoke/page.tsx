import { Button } from "@/components/ui/button";

// Temporary platform smoke page; removed once the new screens land (U7).
export default function SmokePage() {
  return (
    <main className="mx-auto max-w-xl space-y-4 p-16">
      <h1 className="text-2xl font-semibold">Platform smoke</h1>
      <p className="text-muted-foreground">Tailwind tokens and a shadcn button render.</p>
      <Button>Primary</Button>
    </main>
  );
}
