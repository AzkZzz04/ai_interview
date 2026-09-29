"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiRequest } from "@/lib/api/client";
import type { FitView, JobAccepted, SuggestionsView } from "@/lib/api/types";
import { invalidateLibrary, keys } from "./library";

const pairPath = (resumeId: string, targetJobId: string) => `/api/resumes/${resumeId}/target-jobs/${targetJobId}`;

export const scoringKeys = {
  fit: (resumeId: string, targetJobId: string) => ["fit", resumeId, targetJobId] as const,
  suggestions: (resumeId: string, targetJobId: string) => ["suggestions", resumeId, targetJobId] as const
};

export function useScoreResume(resumeId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => apiRequest<JobAccepted>(`/api/resumes/${resumeId}/score`, { method: "POST", body: {} }),
    onSettled: () => Promise.all([
      client.invalidateQueries({ queryKey: keys.resume(resumeId) }),
      client.invalidateQueries({ queryKey: keys.resumes })
    ])
  });
}

export function useFit(resumeId: string, targetJobId: string) {
  return useQuery({
    queryKey: scoringKeys.fit(resumeId, targetJobId),
    queryFn: ({ signal }) => apiRequest<FitView>(`${pairPath(resumeId, targetJobId)}/fit`, { signal })
  });
}

export function useRunFit(resumeId: string, targetJobId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => apiRequest<JobAccepted>(`${pairPath(resumeId, targetJobId)}/fit`, { method: "POST", body: {} }),
    onSettled: () => client.invalidateQueries({ queryKey: scoringKeys.fit(resumeId, targetJobId) })
  });
}

export function useSuggestions(resumeId: string, targetJobId: string) {
  return useQuery({
    queryKey: scoringKeys.suggestions(resumeId, targetJobId),
    queryFn: ({ signal }) => apiRequest<SuggestionsView>(`${pairPath(resumeId, targetJobId)}/suggestions`, { signal })
  });
}

export function useRunSuggestions(resumeId: string, targetJobId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => apiRequest<JobAccepted>(`${pairPath(resumeId, targetJobId)}/suggestions`, { method: "POST", body: {} }),
    onSettled: () => invalidateLibrary(client)
  });
}
