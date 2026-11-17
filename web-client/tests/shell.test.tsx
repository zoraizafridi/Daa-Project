import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { Shell } from "../src/pages/Shell";
import { useApp } from "../src/store";

vi.mock("../src/components/useStream", () => ({ usePortalData: () => undefined }));

const state = { running: false, autoMode: false, user: { username: "admin", role: "Admin" }, alertCount: 0 };

function mount() {
  return render(
    <MemoryRouter initialEntries={["/portal"]}>
      <Routes>
        <Route path="/portal" element={<Shell />}><Route index element={<p>Overview content</p>} /></Route>
        <Route path="/login" element={<p>Sign in</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  useApp.setState({ auth: "unknown", user: null, assets: [], alerts: [], selectedId: null, disconnected: false, notice: "" });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  document.cookie = "csrf=; Max-Age=0; path=/";
});

describe("portal shell", () => {
  test("does not render Overview before the session check and redirects on 401", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 401 } as Response));
    mount();
    expect(screen.queryByText("Overview content")).toBeNull();
    expect(await screen.findByText("Sign in")).toBeTruthy();
    expect(screen.queryByText("Overview content")).toBeNull();
    expect(useApp.getState().user).toBeNull();
  });

  test("search selects the host in Overview rather than using a removed detail route", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => state } as Response));
    useApp.setState({ assets: [{ id: "a", name: "Host A", ip: "10.0.0.1", type: "SERVER", criticality: 1, riskScore: 0, status: "NORMAL" }] });
    mount();
    await screen.findByText("Overview content");
    fireEvent.change(screen.getByRole("searchbox", { name: "Search hosts" }), { target: { value: "Host A" } });
    const hit = screen.getByRole("link", { name: "Host A" });
    expect(hit).toHaveAttribute("href", "/portal");
    fireEvent.click(hit);
    expect(useApp.getState().selectedId).toBe("a");
    expect(screen.getByRole("searchbox", { name: "Search hosts" })).toHaveValue("");
    expect(screen.queryByRole("link", { name: "Hosts" })).toBeNull();
  });

  test("logout posts with CSRF and clears the session", async () => {
    document.cookie = "csrf=logout-token";
    const fetchMock = vi.fn(async (_path: string, _init?: RequestInit) => ({ ok: true, status: 200, json: async () => state } as Response));
    vi.stubGlobal("fetch", fetchMock);
    mount();
    await screen.findByText("Overview content");
    fireEvent.click(screen.getByRole("button", { name: "Account menu, admin" }));
    fireEvent.click(screen.getByRole("button", { name: "Log out" }));
    await waitFor(() => expect(useApp.getState().auth).toBe("out"));
    expect(await screen.findByText("Sign in")).toBeTruthy();
    const logoutCall = fetchMock.mock.calls.find(([path]) => path === "/api/logout")!;
    expect(logoutCall).toBeTruthy();
    expect(logoutCall[1]?.method).toBe("POST");
    expect(logoutCall[1]?.credentials).toBe("same-origin");
    expect((logoutCall[1]?.headers as Headers).get("X-CSRF-Token")).toBe("logout-token");
  });
});
