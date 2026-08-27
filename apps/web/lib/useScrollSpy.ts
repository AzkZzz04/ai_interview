"use client";

import { useEffect, useState } from "react";

/**
 * Tracks which section is currently in view so the sidebar can highlight it.
 * The nav previously hardcoded the active item, so it never moved as the user
 * scrolled through the workspace.
 */
export function useScrollSpy(sectionIds: string[], fallbackId: string) {
  const [activeId, setActiveId] = useState(fallbackId);

  useEffect(() => {
    if (typeof IntersectionObserver === "undefined") {
      return;
    }

    const elements = sectionIds
      .map((id) => document.getElementById(id))
      .filter((element): element is HTMLElement => element !== null);

    if (elements.length === 0) {
      return;
    }

    const visible = new Map<string, number>();
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            visible.set(entry.target.id, entry.intersectionRatio);
          }
          else {
            visible.delete(entry.target.id);
          }
        }

        let best: string | null = null;
        let bestRatio = -1;
        for (const id of sectionIds) {
          const ratio = visible.get(id);
          if (ratio !== undefined && ratio > bestRatio) {
            best = id;
            bestRatio = ratio;
          }
        }
        if (best) {
          setActiveId(best);
        }
      },
      { rootMargin: "-20% 0px -60% 0px", threshold: [0, 0.25, 0.5, 1] }
    );

    for (const element of elements) {
      observer.observe(element);
    }
    return () => observer.disconnect();
  }, [sectionIds]);

  return activeId;
}
