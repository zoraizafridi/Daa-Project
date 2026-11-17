import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { Overview } from "../src/pages/Overview";
import { scenarioReset } from "../src/scenario";
import { useApp } from "../src/store";

vi.mock("../src/components/NetworkMap", () => ({ NetworkStage: () => <div data-testid="network-stage" /> }));
vi.mock("gsap", () => ({ gsap: { set: vi.fn(), fromTo: vi.fn(() => ({ kill: vi.fn() })) } }));

const assets = [
  { id: "a", name: "Host A", ip: "10.0.0.1", type: "SERVER", criticality: 3, riskScore: 72, status: "SUSPICIOUS" },
  { id: "b", name: "Host B", ip: "10.0.0.2", type: "ROUTER", criticality: 1, riskScore: 0, status: "NORMAL" },
];
const alert = {
  id: "server-alert", timestamp: "2026-01-01T00:00:00Z", severity: "HIGH", attackType: "PORT_SCAN",
  sourceIp: assets[1].ip, targetIp: assets[0].ip, detectorName: "SignatureDetector",
  description: "<script>alert('unsafe')</script>", simulated: true,
};

beforeEach(() => {
  window.matchMedia = ((query: string) => ({
    matches: true, media: query, onchange: null, addListener() {}, removeListener() {},
    addEventListener() {}, removeEventListener() {}, dispatchEvent: () => false,
  })) as typeof window.matchMedia;
  scenarioReset();
  useApp.setState({
    auth: "in", user: { username: "viewer", role: "Viewer" }, assets, links: [], alerts: [alert],
    selectedId: "a", running: false, autoMode: false, alertCount: 1, disconnected: false,
  });
});

afterEach(() => {
  cleanup();
  scenarioReset();
});

describe("Overview", () => {
  test("preserves risk on the 0 to 100 scale and inspects a host in place", () => {
    render(<Overview />);
    expect(screen.getByTestId("network-stage")).toBeTruthy();
    expect(screen.getByText("72")).toBeTruthy();
    expect(screen.getByText("/100")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Inspect" }));
    const detail = screen.getByLabelText("Host detail for Host A");
    expect(detail).toHaveFocus();
    expect(within(detail).getByText("Server · criticality 3")).toBeTruthy();
    expect(within(detail).getByText(/server-alert/)).toBeTruthy();
    expect(screen.queryByRole("link")).toBeNull();
    expect(screen.queryByRole("button", { name: "Isolate" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Restore" })).toBeNull();
  });

  test("raw server alerts keep IDs, endpoints, detector and escaped descriptions", () => {
    const { container } = render(<Overview />);
    const raw = screen.getByRole("heading", { name: "Raw alerts" }).closest("section")!;
    expect(within(raw).getByText(/server-alert/).textContent).toContain("10.0.0.2 → 10.0.0.1");
    expect(within(raw).getByText(`SignatureDetector: ${alert.description}`)).toBeTruthy();
    expect(within(raw).getByText("SIMULATION")).toBeTruthy();
    expect(container.querySelector("script")).toBeNull();
    expect(screen.queryByText("Open incidents")).toBeNull();
    expect(screen.queryByLabelText("Alert quality")).toBeNull();
  });

  test("Viewer cannot launch attacks or mutate the live scenario", () => {
    render(<Overview />);
    expect(screen.queryByRole("form", { name: "Basic attack injector" })).toBeNull();
    for (const name of ["Play", "Pause", "Step", "Replay", "1x", "2x", "4x"]) {
      expect(screen.getByRole("button", { name })).toBeDisabled();
    }
  });

  test.each(["Admin", "Analyst"])("%s gets the basic attack injector", (role) => {
    useApp.setState({ user: { username: "operator", role } });
    render(<Overview />);
    expect(screen.getByRole("form", { name: "Basic attack injector" })).toBeTruthy();
  });
});
