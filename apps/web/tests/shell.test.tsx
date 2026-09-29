import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { AppShell } from "@/components/shell/AppShell";

describe("app shell", () => {
  it("lets keyboard users skip to the main content", async () => {
    const user = userEvent.setup();
    render(<AppShell><p>Page</p></AppShell>);
    await user.tab();
    const skip = screen.getByRole("link", { name: "Skip to main content" });
    expect(skip).toHaveFocus();
    expect(skip).toHaveAttribute("href", "#main");
    expect(document.getElementById("main")).toContainElement(screen.getByText("Page"));
  });

  it("opens the mobile menu as a focus-trapping dialog", async () => {
    const user = userEvent.setup();
    render(<AppShell><p>Page</p></AppShell>);
    await user.click(screen.getByRole("button", { name: "Open menu" }));
    const dialog = await screen.findByRole("dialog");
    expect(dialog).toContainElement(document.activeElement as HTMLElement);
    for (let i = 0; i < 8; i++) {
      await user.tab();
      expect(dialog).toContainElement(document.activeElement as HTMLElement);
    }
  });
});
