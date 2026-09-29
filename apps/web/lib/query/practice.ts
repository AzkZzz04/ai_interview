"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiRequest } from "@/lib/api/client";
import type { PracticeSet } from "@/lib/api/types";

export const practiceKeys = {
  set: (id: string) => ["practice-set", id] as const
};

/** Creates the pair's practice set, or returns the existing one (one set per resume and job). */
export function useCreatePracticeSet() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: { resumeId: string; targetJobId: string }) =>
      apiRequest<PracticeSet>("/api/practice-sets", { method: "POST", body: { ...body, mode: "PRACTICE" } }),
    onSuccess: (set) => client.setQueryData(practiceKeys.set(set.id), set)
  });
}

export function usePracticeSet(id: string) {
  return useQuery({
    queryKey: practiceKeys.set(id),
    queryFn: ({ signal }) => apiRequest<PracticeSet>(`/api/practice-sets/${id}`, { signal })
  });
}
