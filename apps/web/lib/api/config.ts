/** Empty by default: the browser calls /api on its own origin (the Vite proxy in dev, nginx in the container). */
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "";
export const REQUEST_TIMEOUT_MS = 15_000;

export type MockMode = "all" | "new-only" | "off";

/** Mocks default to on in development and off in production builds (KTD5). */
export const API_MOCKS: MockMode = parseMockMode(import.meta.env.VITE_API_MOCKS, import.meta.env.PROD);

export function parseMockMode(value: string | undefined, isProd: boolean): MockMode {
  if (value === "all" || value === "new-only" || value === "off") return value;
  return isProd ? "off" : "all";
}
