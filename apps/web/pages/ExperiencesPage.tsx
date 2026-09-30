import { DeleteConfirm } from "@/components/library/DeleteConfirm";
import { InlineRename } from "@/components/library/InlineRename";
import { LinkedInDialog } from "@/components/library/LinkedInDialog";
import { EmptyState, ErrorState, ListSkeleton } from "@/components/library/ListStates";
import { ProjectFormDialog } from "@/components/library/ProjectFormDialog";
import { PageHeader } from "@/components/shell/AppShell";
import { Badge } from "@/components/ui/badge";
import { useExperiences } from "@/lib/query/library";

export default function ExperiencesPage() {
  const experiences = useExperiences();
  const actions = (
    <div className="flex flex-wrap gap-2">
      <ProjectFormDialog />
      <LinkedInDialog />
    </div>
  );
  return (
    <>
      <PageHeader
        title="Experiences"
        description="Projects and past roles that can be suggested when they fit a target job."
        action={actions}
      />
      {experiences.isPending ? <ListSkeleton /> : null}
      {experiences.isError ? <ErrorState error={experiences.error} onRetry={() => experiences.refetch()} /> : null}
      {experiences.data?.items.length === 0 ? (
        <EmptyState
          title="No experiences yet"
          description="Optional. Add a project or paste your LinkedIn experience to get better suggestions."
          action={actions}
        />
      ) : null}
      {experiences.data?.items.length ? (
        <ul className="space-y-3">
          {experiences.data.items.map((item) => (
            <li key={item.id} id={`item-${item.id}`} className="scroll-mt-20 rounded-lg border bg-card px-4 py-3 target:ring-2 target:ring-primary">
              <div className="flex flex-wrap items-start gap-3">
                <div className="min-w-0 flex-1 space-y-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-medium">{item.title}</span>
                    <Badge variant="outline">{item.source === "FORM" ? "Project" : "LinkedIn"}</Badge>
                  </div>
                  {item.organization ? <p className="text-xs text-muted-foreground">{item.organization}</p> : null}
                  <p className="line-clamp-2 text-sm text-muted-foreground">{item.description}</p>
                </div>
                <InlineRename kind="experiences" id={item.id} name={item.title} max={120} />
                <DeleteConfirm kind="experiences" id={item.id} name={item.title} />
              </div>
            </li>
          ))}
        </ul>
      ) : null}
    </>
  );
}
