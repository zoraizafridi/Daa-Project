import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { afterEach, describe, expect, test, vi } from "vitest";
import { applyState, HttpStatusError, request } from "../src/api";
import { sessionAfterStatus } from "../src/session";
import { useApp } from "../src/store";

afterEach(() => vi.unstubAllGlobals());

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return files(path);
    return path.endsWith(".ts") || path.endsWith(".tsx") || path.endsWith(".css") ? [path] : [];
  });
}

describe("session", () => {
  test("a 401 clears the session", () => {
    expect(sessionAfterStatus({ username: "admin", role: "Admin" }, 401)).toBeNull();
    expect(sessionAfterStatus({ username: "admin", role: "Admin" }, 200)).toEqual({
      username: "admin",
      role: "Admin",
    });
  });

  test("an unauthorized API response clears the in-memory session", async () => {
    useApp.getState().setUser({ username: "admin", role: "Admin" });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 401 } as Response));
    await expect(request("/api/state")).rejects.toBeInstanceOf(HttpStatusError);
    expect(useApp.getState().auth).toBe("out");
    expect(useApp.getState().user).toBeNull();
  });

  test("a rejected login does not replace the current session", async () => {
    useApp.getState().setUser({ username: "admin", role: "Admin" });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 401 } as Response));
    await expect(request("/api/login", { method: "POST", body: JSON.stringify({ username: "wrong", password: "wrong" }) })).rejects.toBeInstanceOf(HttpStatusError);
    expect(useApp.getState().user).toEqual({ username: "admin", role: "Admin" });
  });

  test("state applies only the basic simulation contract", () => {
    applyState({ running: true, autoMode: false, user: { username: "analyst", role: "Analyst" }, alertCount: 4 });
    expect(useApp.getState()).toMatchObject({ auth: "in", running: true, autoMode: false, alertCount: 4 });
    expect(useApp.getState().user).toEqual({ username: "analyst", role: "Analyst" });
    expect("incidentCount" in useApp.getState()).toBe(false);
    expect("containedIps" in useApp.getState()).toBe(false);
  });

  test("the client keeps the session in memory and does not use browser storage", () => {
    const stored = files(join(process.cwd(), "src")).filter((path) => {
      const text = readFileSync(path, "utf8");
      return text.includes("localStorage") || text.includes("sessionStorage");
    });
    expect(stored.every((path) => path.endsWith("Shell.tsx"))).toBe(true);
    const shell = stored.length ? readFileSync(stored[0], "utf8") : "";
    expect(shell.includes("fs-nav-collapsed")).toBe(true);
    expect(shell.includes("sessionStorage")).toBe(false);
  });
});
