import { createRootRoute, createRoute, createRouter, Outlet, type RouterHistory } from "@tanstack/react-router";
import { AppShell } from "@/components/shell/AppShell";
import { DeletedState } from "@/components/library/DeletedState";
import { Toaster } from "@/components/ui/sonner";
import ExperiencesPage from "@/pages/ExperiencesPage";
import FitPage from "@/pages/FitPage";
import HistoryPage from "@/pages/HistoryPage";
import HomePage from "@/pages/HomePage";
import PracticePage from "@/pages/PracticePage";
import ResumePickerPage from "@/pages/ResumePickerPage";
import ResumesPage from "@/pages/ResumesPage";
import ScorePage from "@/pages/ScorePage";
import TargetJobPickerPage from "@/pages/TargetJobPickerPage";
import TargetJobsPage from "@/pages/TargetJobsPage";

const rootRoute = createRootRoute({
  component: () => (
    <>
      <AppShell><Outlet /></AppShell>
      <Toaster position="bottom-right" />
    </>
  ),
  // Unknown paths get the same page as links to deleted items.
  notFoundComponent: () => <DeletedState />
});

const routeTree = rootRoute.addChildren([
  createRoute({ getParentRoute: () => rootRoute, path: "/", component: HomePage }),
  createRoute({ getParentRoute: () => rootRoute, path: "/flow", component: ResumePickerPage }),
  createRoute({ getParentRoute: () => rootRoute, path: "/flow/$resumeId", component: ScorePage }),
  createRoute({ getParentRoute: () => rootRoute, path: "/flow/$resumeId/jobs", component: TargetJobPickerPage }),
  createRoute({ getParentRoute: () => rootRoute, path: "/flow/$resumeId/jobs/$jobId", component: FitPage }),
  createRoute({ getParentRoute: () => rootRoute, path: "/practice/$setId", component: PracticePage }),
  createRoute({ getParentRoute: () => rootRoute, path: "/library/resumes", component: ResumesPage }),
  createRoute({ getParentRoute: () => rootRoute, path: "/library/jobs", component: TargetJobsPage }),
  createRoute({ getParentRoute: () => rootRoute, path: "/library/experiences", component: ExperiencesPage }),
  createRoute({ getParentRoute: () => rootRoute, path: "/history", component: HistoryPage })
]);

export function makeRouter(history?: RouterHistory) {
  return createRouter({ routeTree, history, scrollRestoration: true });
}

export const router = makeRouter();

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router;
  }
}
