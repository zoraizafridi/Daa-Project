import { cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, test, vi } from "vitest";
import { usePortalData } from "../src/components/useStream";
import { useApp } from "../src/store";

const assets = [{ id: "a", name: "Host A", ip: "10.0.0.1", type: "SERVER", criticality: 1, riskScore: 42, status: "SUSPICIOUS" }];
const alert = {
  id: "live-alert", timestamp: "2026-01-01T00:00:00Z", severity: "HIGH", attackType: "PORT_SCAN",
  sourceIp: "10.0.0.2", targetIp: "10.0.0.1", detectorName: "SignatureDetector", description: "Detected scan", simulated: true,
};

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("portal stream contract", () => {
  test("loads only network and alerts and listens to alert, network and state", async () => {
    const handlers = new Map<string, (event: MessageEvent) => void>();
    const close = vi.fn();
    const subscribe = vi.fn((name: string, handler: (event: MessageEvent) => void) => handlers.set(name, handler));
    const streamUrls: string[] = [];
    vi.stubGlobal("EventSource", class {
      constructor(url: string) { streamUrls.push(url); }
      addEventListener = subscribe;
      close = close;
      onerror = null;
    });
    const fetchMock = vi.fn(async (path: string) => ({
      ok: true, status: 200,
      json: async () => path === "/api/network" ? { assets, links: [], teams: [{ name: "SOC", capacity: 2 }] } : [alert],
    } as Response));
    vi.stubGlobal("fetch", fetchMock);
    useApp.setState({ auth: "in", user: { username: "admin", role: "Admin" }, assets: [], links: [], alerts: [], riskHistory: {}, teams: [] });
    const { rerender } = renderHook(({ active }) => usePortalData(active), { initialProps: { active: true } });
    await waitFor(() => expect(useApp.getState().alerts).toEqual([alert]));
    expect(streamUrls).toEqual(["/api/stream"]);
    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual(["/api/network", "/api/alerts"]);
    expect([...handlers.keys()]).toEqual(["open", "alert", "network", "state"]);
    expect(useApp.getState().teams).toEqual([{ name: "SOC", capacity: 2 }]);

    handlers.get("network")!(new MessageEvent("network", { data: JSON.stringify({ assets: [{ ...assets[0], riskScore: 60 }], links: [], teams: [] }) }));
    expect(useApp.getState().riskHistory.a).toEqual([42, 60]);
    handlers.get("alert")!(new MessageEvent("alert", { data: JSON.stringify({ ...alert, id: "next-alert" }) }));
    expect(useApp.getState().cue?.id).toBe("next-alert");
    handlers.get("state")!(new MessageEvent("state", { data: JSON.stringify({ running: true, autoMode: true, user: { username: "admin", role: "Admin" }, alertCount: 2 }) }));
    expect(useApp.getState()).toMatchObject({ running: true, autoMode: true, alertCount: 2 });
    rerender({ active: false });
    expect(close).toHaveBeenCalledTimes(1);
  });
});
