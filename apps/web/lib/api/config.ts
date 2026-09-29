export const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://127.0.0.1:8080";
export const REQUEST_TIMEOUT_MS = 15_000;

export type MockMode = "all" | "new-only" | "off";

/** Mocks default to on in development and off in production builds (KTD5). */
export const API_MOCKS: MockMode = parseMockMode(process.env.NEXT_PUBLIC_API_MOCKS);

function parseMockMode(value: string | undefined): MockMode {
  if (value === "all" || value === "new-only" || value === "off") return value;
  return process.env.NODE_ENV === "production" ? "off" : "all";
}
