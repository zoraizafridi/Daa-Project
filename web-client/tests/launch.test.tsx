import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { AttackControls } from "../src/components/AttackControls";
import { scenarioReset, scenarioStart, scenarioStep } from "../src/scenario";
import { useApp } from "../src/store";

const hosts = [
  { id: "a", name: "Host A", ip: "10.0.0.1", type: "SERVER", criticality: 1, riskScore: 0, status: "NORMAL" },
  { id: "b", name: "Host B", ip: "10.0.0.2", type: "ROUTER", criticality: 1, riskScore: 0, status: "NORMAL" },
];

beforeEach(() => {
  useApp.setState({ auth: "in", user: { username: "admin", role: "Admin" }, assets: hosts, alerts: [], selectedId: null, notice: "" });
});

afterEach(() => {
  cleanup();
  scenarioReset();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  document.cookie = "csrf=; Max-Age=0; path=/";
});

describe("basic attack launch", () => {
  test("Launch is disabled with an invalid selection", () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    render(<AttackControls />);
    const launch = screen.getByRole("button", { name: "Launch" });
    expect(launch).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Source"), { target: { value: "a" } });
    fireEvent.change(screen.getByLabelText("Target"), { target: { value: "a" } });
    expect(launch).toBeDisabled();
    fireEvent.submit(screen.getByRole("form", { name: "Basic attack injector" }));
    expect(fetchMock).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText("Target"), { target: { value: "b" } });
    expect(launch).not.toBeDisabled();
  });

  test.each(["PORT_SCAN", "BRUTE_FORCE", "DOS_FLOOD", "DATA_TRANSFER"])("posts %s with asset IDs and CSRF", async (type) => {
    document.cookie = "csrf=launch-token";
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200 } as Response);
    vi.stubGlobal("fetch", fetchMock);
    render(<AttackControls />);
    expect(screen.getByLabelText("Type").querySelectorAll("option")).toHaveLength(4);
    fireEvent.change(screen.getByLabelText("Type"), { target: { value: type } });
    fireEvent.change(screen.getByLabelText("Source"), { target: { value: "a" } });
    fireEvent.change(screen.getByLabelText("Target"), { target: { value: "b" } });
    fireEvent.click(screen.getByRole("button", { name: "Launch" }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe("/api/attacks");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual({ type, src: "a", dst: "b" });
    expect(init.credentials).toBe("same-origin");
    expect((init.headers as Headers).get("X-CSRF-Token")).toBe("launch-token");
    await waitFor(() => expect(screen.getByRole("button", { name: "Launch" })).not.toBeDisabled());
    expect(useApp.getState().alerts).toEqual([]);
  });

  test.each(["Admin", "Analyst"])("allows %s to use the selected host", (role) => {
    useApp.setState({ user: { username: "operator", role }, selectedId: "a" });
    render(<AttackControls />);
    fireEvent.click(screen.getByRole("button", { name: "Use selection as source" }));
    expect(screen.getByLabelText("Source")).toHaveValue("a");
  });

  test("a Viewer has no attack form", () => {
    useApp.setState({ user: { username: "viewer", role: "Viewer" } });
    render(<AttackControls />);
    expect(screen.queryByRole("form")).toBeNull();
    expect(screen.queryByRole("button", { name: "Launch" })).toBeNull();
  });

  test("scenario animation uses server alerts and stops posting after a role change", () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200 } as Response);
    vi.stubGlobal("fetch", fetchMock);
    scenarioStart(false);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(useApp.getState().alerts).toEqual([]);
    useApp.setState({ user: { username: "viewer", role: "Viewer" } });
    scenarioStep();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
