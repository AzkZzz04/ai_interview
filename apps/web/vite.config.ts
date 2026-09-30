import path from "node:path";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, ".")
    }
  },
  // The backend's CORS allows only port 3000, so never fall back to another port.
  server: { port: 3000, strictPort: true },
  preview: { port: 3000, strictPort: true },
  test: {
    environment: "jsdom",
    setupFiles: ["./tests/setup.ts"],
    clearMocks: true,
    restoreMocks: true
  }
});
