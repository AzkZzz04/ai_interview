import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { renderRoute, setupMockBackend } from "./render";

setupMockBackend();

describe("app shell", () => {
  it("lets keyboard users skip to the main content", async () => {
    const user = userEvent.setup();
    renderRoute("/");
    const heading = await screen.findByRole("heading", { level: 1 });
    await user.tab();
    const skip = screen.getByRole("link", { name: "Skip to main content" });
    expect(skip).toHaveFocus();
    expect(skip).toHaveAttribute("href", "#main");
    expect(document.getElementById("main")).toContainElement(heading);
  });

  it("opens the mobile menu as a focus-trapping dialog", async () => {
    const user = userEvent.setup();
    renderRoute("/");
    await user.click(await screen.findByRole("button", { name: "Open menu" }));
    const dialog = await screen.findByRole("dialog");
    expect(dialog).toContainElement(document.activeElement as HTMLElement);
    for (let i = 0; i < 8; i++) {
      await user.tab();
      expect(dialog).toContainElement(document.activeElement as HTMLElement);
    }
  });

  it("marks the current section in the side menu", async () => {
    renderRoute("/library/jobs");
    const nav = (await screen.findAllByRole("navigation", { name: "Main" }))[0];
    expect(within(nav).getByRole("link", { name: "Target jobs" })).toHaveAttribute("aria-current", "page");
    expect(within(nav).getByRole("link", { name: "Home" })).not.toHaveAttribute("aria-current");
  });
});
