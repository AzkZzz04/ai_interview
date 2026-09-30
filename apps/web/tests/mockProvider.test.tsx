import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { MockProvider } from "@/app/MockProvider";

const startMockWorker = vi.hoisted(() => vi.fn());
vi.mock("@/mocks/browser", () => ({ startMockWorker }));

describe("mock provider", () => {
  it("blocks the app when the worker fails, then starts it on Retry", async () => {
    startMockWorker.mockRejectedValueOnce(new Error("no service worker")).mockResolvedValueOnce(undefined);
    const user = userEvent.setup();
    render(<MockProvider mode="all"><p>App</p></MockProvider>);
    expect(await screen.findByRole("alert")).toHaveTextContent("Mock data could not start");
    expect(screen.queryByText("App")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByText("App")).toBeInTheDocument();
    expect(startMockWorker).toHaveBeenCalledTimes(2);
  });

  it("renders the app at once and never starts the worker when mocks are off", () => {
    render(<MockProvider mode="off"><p>App</p></MockProvider>);
    expect(screen.getByText("App")).toBeInTheDocument();
    expect(startMockWorker).not.toHaveBeenCalled();
  });
});
