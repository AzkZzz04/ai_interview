import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";

import { EmptyState } from "@/components/EmptyState";
import { InsightList } from "@/components/InsightList";

describe("EmptyState", () => {
  it("renders the icon, title, and helper text", () => {
    render(<EmptyState icon={<span data-testid="icon" />} title="No resume yet" text="Upload one to begin" />);

    expect(screen.getByTestId("icon")).toBeInTheDocument();
    expect(screen.getByText("No resume yet")).toBeInTheDocument();
    expect(screen.getByText("Upload one to begin")).toBeInTheDocument();
  });
});

describe("InsightList", () => {
  it("renders every item under the titled tone", () => {
    const { container } = render(
      <InsightList title="Strengths" tone="positive" items={["Clear impact", "Strong ownership"]} />
    );

    expect(screen.getByText("Strengths")).toBeInTheDocument();
    expect(screen.getByText("Clear impact")).toBeInTheDocument();
    expect(screen.getByText("Strong ownership")).toBeInTheDocument();
    expect(container.querySelector(".insight-list.positive")).not.toBeNull();
  });

  it("applies the warning tone class", () => {
    const { container } = render(<InsightList title="Gaps" tone="warning" items={["Add metrics"]} />);

    expect(container.querySelector(".insight-list.warning")).not.toBeNull();
  });
});
