import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { Suspense } from "react";
import { MemoryRouter, Outlet, useLocation } from "react-router-dom";
import { afterEach, describe, expect, test, vi } from "vitest";
import { AppRoutes } from "../src/App";
import { useApp } from "../src/store";

vi.mock("../src/pages/Shell", () => ({
  Shell: () => (
    <div>
      <span data-testid="shell" />
      <Outlet />
    </div>
  ),
}));

vi.mock("../src/pages/Overview", () => ({ Overview: () => <h1>Overview</h1> }));
vi.mock("../src/pages/Reconnection", () => ({ Reconnection: () => <h1>Reconnection</h1> }));
vi.mock("../src/pages/Mitigation", () => ({ Mitigation: () => <h1>Mitigation</h1> }));
vi.mock("../src/pages/Pipeline", () => ({ Pipeline: () => <h1>Pipeline</h1> }));

vi.mock("lenis", () => ({
  default: class {
    on() {}
    stop() {}
    raf() {}
    scrollTo() {}
    destroy() {}
  },
}));

function PathProbe() {
  const location = useLocation();
  return <span data-testid="path">{location.pathname}</span>;
}

function mount(path: string) {
  window.matchMedia = (query: string) =>
    ({
      matches: query.includes("reduce"),
      media: query,
      onchange: null,
      addListener: () => undefined,
      removeListener: () => undefined,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      dispatchEvent: () => false,
    }) as MediaQueryList;
  return render(
    <MemoryRouter initialEntries={[path]}>

      <Suspense fallback={<p>loading</p>}>
        <AppRoutes />
      </Suspense>
      <PathProbe />
    </MemoryRouter>,
  );
}

describe("portal routing", () => {
  afterEach(cleanup);

  test("the legacy overview address redirects to the portal index", async () => {
    mount("/portal/overview");
    await waitFor(() => expect(screen.getByTestId("path").textContent).toBe("/portal"), { timeout: 5000 });
    expect(await screen.findByRole("heading", { name: "Overview" })).toBeTruthy();
  });

  test.each(["reconnection", "mitigation", "pipeline"])("Admin can open exact %s route", async (page) => {
    useApp.setState({ auth: "in", user: { username: "admin", role: "Admin" } });
    mount(`/portal/${page}`);
    expect(await screen.findByRole("heading", { name: page[0].toUpperCase() + page.slice(1) })).toBeTruthy();
    expect(screen.getByTestId("path").textContent).toBe(`/portal/${page}`);
  });

  test.each(["Analyst", "Viewer", "admin"])("%s cannot directly enter any algorithm workspace", async (role) => {
    useApp.setState({ auth: "in", user: { username: "operator", role } });
    for (const page of ["reconnection", "mitigation", "pipeline"]) {
      mount(`/portal/${page}`);
      expect(await screen.findByRole("heading", { name: "Access denied" })).toBeTruthy();
      expect(screen.queryByRole("heading", { name: page[0].toUpperCase() + page.slice(1) })).toBeNull();
      cleanup();
    }
  });

  test("signed-out algorithm navigation redirects to Login", async () => {
    useApp.setState({ auth: "out", user: null });
    mount("/portal/pipeline");
    await waitFor(() => expect(screen.getByTestId("path").textContent).toBe("/login"));
    expect(screen.getByLabelText("Username")).toBeTruthy();
  });

  test("unknown authentication never mounts an algorithm workspace", async () => {
    useApp.setState({ auth: "unknown", user: null });
    mount("/portal/reconnection");
    expect(await screen.findByRole("status")).toHaveTextContent("Checking session");
    expect(screen.queryByRole("heading", { name: "Reconnection" })).toBeNull();
  });

  test("unknown portal addresses render the not found page", async () => {
    mount("/portal/nope");
    expect(await screen.findByRole("heading", { name: "Not found" })).toBeTruthy();
    expect(screen.getByTestId("path").textContent).toBe("/portal/nope");
  });

  test.each(["admin", "alerts", "events", "flows", "incidents", "hosts", "hosts/a", "import", "planner", "reports", "control", "attack"])("removed portal route %s is not found", async (page) => {
    mount(`/portal/${page}`);
    expect(await screen.findByRole("heading", { name: "Not found" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "Back to overview" })).toHaveAttribute("href", "/portal");
  });

  test("the removed roles route is not found", async () => {
    mount("/roles");
    expect(await screen.findByRole("heading", { name: "Not found" })).toBeTruthy();
    expect(screen.queryByTestId("shell")).toBeNull();
  });

  test("the signed-out landing page enters Login outside the portal shell", async () => {
    useApp.setState({ auth: "out", user: null });
    mount("/");
    expect(screen.queryByTestId("shell")).toBeNull();
    const heading = await screen.findByRole("heading", { level: 1 });
    expect(heading.textContent ?? "").toMatch(/Detection/);
    fireEvent.click(screen.getAllByRole("button", { name: /Enter portal/i })[0]);
    await waitFor(() => expect(screen.getByTestId("path").textContent).toBe("/login"));
    expect(screen.getByLabelText("Username")).toBeTruthy();
  });
});
