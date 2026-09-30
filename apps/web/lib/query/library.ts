import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import { apiRequest } from "@/lib/api/client";
import type {
  DeleteImpact,
  History,
  Experience,
  ExperienceBatchResult,
  ExperienceCreated,
  ExperienceInput,
  JobAccepted,
  Page,
  PasteResumeRequest,
  Resume,
  ResumeCreated,
  ResumeDetail,
  TargetJob,
  TargetJobCreated,
  TargetJobDetail,
  UpdateResumeRequest
} from "@/lib/api/types";

export type LibraryKind = "resumes" | "target-jobs" | "experiences";

export const keys = {
  resumes: ["resumes"] as const,
  resume: (id: string) => ["resume", id] as const,
  targetJobs: ["target-jobs"] as const,
  targetJob: (id: string) => ["target-job", id] as const,
  experiences: ["experiences"] as const,
  deleteImpact: (kind: LibraryKind, id: string) => ["delete-impact", kind, id] as const,
  history: ["history"] as const
};

const LIST_POLL_MS = 1_500;

/** Library changes can affect every derived view, so refresh them all. */
export function invalidateLibrary(client: QueryClient) {
  return client.invalidateQueries({
    predicate: (query) => query.queryKey[0] !== "job"
  });
}

// ---- resumes -----------------------------------------------------------------------------------

export function useResumes() {
  return useQuery({
    queryKey: keys.resumes,
    queryFn: ({ signal }) => apiRequest<Page<Resume>>("/api/resumes", { signal }),
    // Keep processing rows moving until extraction finishes.
    refetchInterval: (query) =>
      query.state.data?.items.some((resume) => resume.status === "PROCESSING") ? LIST_POLL_MS : false
  });
}

export function useResume(id: string | undefined, { enabled = true } = {}) {
  return useQuery({
    queryKey: keys.resume(id ?? ""),
    queryFn: ({ signal }) => apiRequest<ResumeDetail>(`/api/resumes/${id}`, { signal }),
    enabled: Boolean(id) && enabled
  });
}

export function useUploadResume() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ file, name, jobTitle }: { file: File; name: string; jobTitle?: string | null }) => {
      const form = new FormData();
      form.append("file", file);
      form.append("name", name);
      if (jobTitle) form.append("jobTitle", jobTitle);
      return apiRequest<ResumeCreated>("/api/resumes", { method: "POST", body: form });
    },
    onSuccess: () => invalidateLibrary(client)
  });
}

export function usePasteResume() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: PasteResumeRequest) => apiRequest<ResumeCreated>("/api/resumes/paste", { method: "POST", body }),
    onSuccess: () => invalidateLibrary(client)
  });
}

export function useUpdateResume() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ id, ...body }: UpdateResumeRequest & { id: string }) =>
      apiRequest<Resume>(`/api/resumes/${id}`, { method: "PATCH", body }),
    onSuccess: () => invalidateLibrary(client)
  });
}

// ---- target jobs -------------------------------------------------------------------------------

export function useTargetJobs() {
  return useQuery({
    queryKey: keys.targetJobs,
    queryFn: ({ signal }) => apiRequest<Page<TargetJob>>("/api/target-jobs", { signal })
  });
}

export function useTargetJob(id: string | undefined, { enabled = true } = {}) {
  return useQuery({
    queryKey: keys.targetJob(id ?? ""),
    queryFn: ({ signal }) => apiRequest<TargetJobDetail>(`/api/target-jobs/${id}`, { signal }),
    enabled: Boolean(id) && enabled
  });
}

export function useCreateTargetJob() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: { name: string; text: string }) =>
      apiRequest<TargetJobCreated>("/api/target-jobs", { method: "POST", body }),
    onSuccess: () => invalidateLibrary(client)
  });
}

// ---- experiences -------------------------------------------------------------------------------

export function useExperiences() {
  return useQuery({
    queryKey: keys.experiences,
    queryFn: ({ signal }) => apiRequest<Page<Experience>>("/api/experiences", { signal })
  });
}

export function useCreateExperience() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: ExperienceInput) => apiRequest<ExperienceCreated>("/api/experiences", { method: "POST", body }),
    onSuccess: () => invalidateLibrary(client)
  });
}

export function useSplitLinkedIn() {
  return useMutation({
    mutationFn: (text: string) =>
      apiRequest<JobAccepted>("/api/experiences/linkedin-split", { method: "POST", body: { text } })
  });
}

export function useSaveExperiences() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (items: ExperienceInput[]) =>
      apiRequest<ExperienceBatchResult>("/api/experiences/batch", { method: "POST", body: { items } }),
    onSuccess: () => invalidateLibrary(client)
  });
}

// ---- shared rename and delete ------------------------------------------------------------------

const RENAME_FIELD: Record<LibraryKind, string> = { resumes: "name", "target-jobs": "name", experiences: "title" };

export function useRename(kind: LibraryKind) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ id, name }: { id: string; name: string }) =>
      apiRequest(`/api/${kind}/${id}`, { method: "PATCH", body: { [RENAME_FIELD[kind]]: name } }),
    onSuccess: () => invalidateLibrary(client)
  });
}

export function useDeleteImpact(kind: LibraryKind, id: string, enabled: boolean) {
  return useQuery({
    queryKey: keys.deleteImpact(kind, id),
    queryFn: ({ signal }) => apiRequest<DeleteImpact>(`/api/${kind}/${id}/delete-impact`, { signal }),
    enabled,
    staleTime: 0
  });
}

export function useDelete(kind: LibraryKind) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => apiRequest<void>(`/api/${kind}/${id}`, { method: "DELETE" }),
    onSuccess: () => invalidateLibrary(client)
  });
}

export function useHistory() {
  return useQuery({
    queryKey: keys.history,
    queryFn: ({ signal }) => apiRequest<History>("/api/history", { signal })
  });
}
