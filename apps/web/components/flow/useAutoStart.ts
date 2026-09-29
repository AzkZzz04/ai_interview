import { useEffect, useRef } from "react";

/** Calls `start` once per `key` while `when` holds; the ref guard survives strict-mode double effects. */
export function useAutoStart(when: boolean, key: string, start: () => void) {
  const startedFor = useRef<string | null>(null);
  useEffect(() => {
    if (!when || startedFor.current === key) return;
    startedFor.current = key;
    start();
  });
}
