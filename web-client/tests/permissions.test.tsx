import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, test } from "vitest";
import { NavList } from "../src/components/NavList";
import { SimControls } from "../src/pages/Control";
import { canControlSimulation, canLaunchAttack, canUseDaa, navEntries } from "../src/permissions";
import { useApp } from "../src/store";

afterEach(cleanup);

describe("role gating", () => {
  test.each(["Analyst", "Viewer", "", "admin"])("%s navigation contains only Overview", (role) => {
    expect(canUseDaa(role)).toBe(false);
    expect(navEntries(role)).toEqual([{ label: "Overview", to: "/portal" }]);
    render(<MemoryRouter><NavList role={role} /></MemoryRouter>);
    expect(screen.getAllByRole("link")).toHaveLength(1);
    expect(screen.getByRole("link", { name: "Overview" })).toHaveAttribute("href", "/portal");
    for (const name of ["Reconnection", "Mitigation", "Pipeline"]) expect(screen.queryByRole("link", { name })).toBeNull();
  });

  test("Admin navigation includes the exact three algorithm routes", () => {
    expect(canUseDaa("Admin")).toBe(true);
    expect(navEntries("Admin")).toEqual([
      { label: "Overview", to: "/portal" },
      { label: "Reconnection", to: "/portal/reconnection" },
      { label: "Mitigation", to: "/portal/mitigation" },
      { label: "Pipeline", to: "/portal/pipeline" },
    ]);
    render(<MemoryRouter><NavList role="Admin" /></MemoryRouter>);
    expect(screen.getAllByRole("link")).toHaveLength(4);
    for (const entry of navEntries("Admin")) expect(screen.getByRole("link", { name: entry.label })).toHaveAttribute("href", entry.to);
  });

  test.each(["Admin", "Analyst"])("%s can control basic simulation and attacks", (role) => {
    expect(canLaunchAttack(role)).toBe(true);
    expect(canControlSimulation(role)).toBe(true);
    useApp.setState({ auth: "in", user: { username: "operator", role }, running: false, autoMode: false });
    render(<SimControls />);
    expect(screen.getByRole("button", { name: "Start" })).toBeTruthy();
  });

  test.each(["Viewer", "", "admin"])("role %s cannot mutate the simulation", (role) => {
    expect(canLaunchAttack(role)).toBe(false);
    expect(canControlSimulation(role)).toBe(false);
    useApp.setState({ user: { username: "viewer", role } });
    render(<SimControls />);
    expect(screen.queryByRole("button")).toBeNull();
  });
});
