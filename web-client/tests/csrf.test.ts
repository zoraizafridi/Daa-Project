import { afterEach, describe, expect, test, vi } from "vitest";
import { request } from "../src/api";

function ok(body: unknown) {
  return { ok: true, status: 200, json: async () => body } as Response;
}

describe("csrf protected requests", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    document.cookie = "csrf=; Max-Age=0; path=/";
  });

  test.each(["POST", "PUT", "PATCH", "DELETE"])("%s requests carry the csrf token and same-origin cookies", async (method) => {
    document.cookie = "csrf=abc123";
    const fetchMock = vi.fn().mockResolvedValue(ok({}));
    vi.stubGlobal("fetch", fetchMock);
    await request("/api/sim/start", { method });
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect((init.headers as Headers).get("X-CSRF-Token")).toBe("abc123");
    expect(init.credentials).toBe("same-origin");
  });

  test.each(["GET", "HEAD"])("%s requests stay token free", async (method) => {
    document.cookie = "csrf=abc123";
    const fetchMock = vi.fn().mockResolvedValue(ok([]));
    vi.stubGlobal("fetch", fetchMock);
    await request("/api/alerts", { method });
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect((init.headers as Headers).has("X-CSRF-Token")).toBe(false);
  });
});
