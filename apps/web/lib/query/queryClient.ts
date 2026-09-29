import { QueryClient } from "@tanstack/react-query";

// The API client already retries retryable failures, so queries do not retry again.
export function makeQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, refetchOnWindowFocus: false, staleTime: 5_000 },
      mutations: { retry: false }
    }
  });
}
