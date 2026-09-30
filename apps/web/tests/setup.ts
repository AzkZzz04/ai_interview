import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, vi } from "vitest";

// jsdom has no scrolling; the router restores scroll on every navigation.
window.scrollTo = vi.fn();

Object.defineProperty(window, "matchMedia", {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn()
  })
});

Object.defineProperty(navigator, "clipboard", {
  configurable: true,
  value: { writeText: vi.fn().mockResolvedValue(undefined), readText: vi.fn().mockResolvedValue("") }
});

afterEach(() => {
  cleanup();
  window.localStorage.clear();
  window.sessionStorage.clear();
});
