import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": import.meta.dirname
    }
  },
  // /api goes through this origin to the local API, as nginx does in the container, so no CORS is involved.
  server: { port: 3000, strictPort: true, proxy: { "/api": "http://127.0.0.1:8080" } },
  preview: { port: 3000, strictPort: true },
  test: {
    environment: "jsdom",
    setupFiles: ["./tests/setup.ts"],
    clearMocks: true,
    restoreMocks: true
  }
});
