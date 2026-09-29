"use client";

import { QueryClientProvider } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import { makeQueryClient } from "@/lib/query/queryClient";
import { MockProvider } from "./MockProvider";

export function Providers({ children }: { children: ReactNode }) {
  const [client] = useState(makeQueryClient);
  return (
    <MockProvider>
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    </MockProvider>
  );
}
