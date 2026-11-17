import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { HttpStatusError, readJson } from "../src/api";
import { daaApi, validRepairSelection, type AdjustedTask, type Assignment, type Backbone, type CompareResponse, type PipelineReport, type Scenario, type SensitivityResponse } from "../src/daa";
import { ScenarioMap } from "../src/components/ScenarioMap";
import { Reconnection } from "../src/pages/Reconnection";
import { Mitigation } from "../src/pages/Mitigation";
import { Pipeline } from "../src/pages/Pipeline";
import { getScenario } from "../src/scenario";
import { useApp } from "../src/store";

vi.mock("../src/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/api")>();
  return { ...actual, readJson: vi.fn() };
});

const main: Scenario = {
  id: "main", name: "Main example", socHostId: "soc",
  hosts: [
    { id: "soc", name: "SOC", riskScore: 0, criticality: 5 },
    { id: "a", name: "Host A", riskScore: 90, criticality: 4 },
    { id: "b", name: "Host B", riskScore: 80, criticality: 3 },
    { id: "c", name: "Host C", riskScore: 50, criticality: 2 },
    { id: "d", name: "Host D", riskScore: 40, criticality: 2 },
    { id: "orphan", name: "Isolated host", riskScore: 10, criticality: 1 },
  ],
  links: [
    { id: "e1", sourceId: "soc", targetId: "a", cost: 10, compromised: false },
    { id: "e2", sourceId: "a", targetId: "b", cost: 15, compromised: false },
    { id: "e3", sourceId: "b", targetId: "c", cost: 21, compromised: false },
    { id: "e4", sourceId: "c", targetId: "d", cost: 20, compromised: false },
    { id: "e5", sourceId: "soc", targetId: "d", cost: 45, compromised: true },
  ],
  teams: [{ id: "Alpha", capacity: 8 }, { id: "Beta", capacity: 4 }],
  tasks: [
    { id: "t1", hostName: "Host A", effortHours: 4, baseValue: 100, daysUnpatched: 10 },
    { id: "t2", hostName: "Isolated host", effortHours: 1, baseValue: 10, daysUnpatched: 0 },
    { id: "t3", hostName: "Host C", effortHours: 9, baseValue: 80, daysUnpatched: 1 },
    { id: "t4", hostName: "Host D", effortHours: 3, baseValue: 60, daysUnpatched: 5 },
  ],
};
const compact: Scenario = { id: "compact", name: "Compact example", socHostId: "solo", hosts: [{ id: "solo", name: "Solo SOC", riskScore: 0, criticality: 1 }], links: [], teams: [], tasks: [] };
const catalog = [compact, main].map((scenario) => ({ id: scenario.id, name: scenario.name, hostCount: scenario.hosts.length, linkCount: scenario.links.length, teamCount: scenario.teams.length, taskCount: scenario.tasks.length }));
const tasks: AdjustedTask[] = main.tasks.map((task, index) => ({ ...task, hostId: ["a", "orphan", "c", "d"][index], adjustedValue: [200, 10, 88, 90][index] }));
const backbone: Backbone = {
  chosenLinks: main.links.slice(0, 4), sortedCandidates: [main.links[0], main.links[1], main.links[3], main.links[2]],
  totalWeight: 66, connected: false, unreachable: [{ id: "orphan", name: "Isolated host" }],
  operations: { comparisons: 12, findCalls: 8, unions: 4, edgesScanned: 4 }, medianNanos: 123000, warmupRuns: 3, measuredRuns: 9,
};
const dp: Assignment = {
  solver: "DP", totalValue: 290, stateCount: 450, transitionOps: 789, medianNanos: 2500000, warmupRuns: 3, measuredRuns: 9,
  teams: [{ id: "Alpha", capacity: 8, usedHours: 4, tasks: [tasks[0]] }, { id: "Beta", capacity: 4, usedHours: 3, tasks: [tasks[3]] }],
  excluded: [{ task: tasks[1], reason: "Unreachable from SOC" }], unassigned: [{ task: tasks[2], reason: "Effort exceeds team capacity" }],
};
const comparison: CompareResponse = {
  scenario: main, tasks, ageGrowthRate: 0.1, dp,
  greedy: { ...dp, solver: "GREEDY", totalValue: 200, stateCount: 4, transitionOps: 12, teams: [{ id: "Alpha", capacity: 8, usedHours: 4, tasks: [tasks[0]] }, { id: "Beta", capacity: 4, usedHours: 0, tasks: [] }] },
  bruteForce: { ...dp, solver: "BRUTE_FORCE", stateCount: 81, transitionOps: 162 }, bruteForceError: null,
};
const report: PipelineReport = { scenario: main, activeHosts: main.hosts, activeLinks: main.links, ageGrowthRate: 0.1, tasks, backbone, assignment: dp, droppedTasks: [{ task: main.tasks[1], reason: "Host disconnected" }] };
const afterReport: PipelineReport = {
  ...report, activeHosts: main.hosts.filter((host) => !["a", "b"].includes(host.id)), activeLinks: [main.links[3]],
  backbone: { ...backbone, chosenLinks: [main.links[3]], totalWeight: 45, unreachable: [{ id: "c", name: "Host C" }] },
  assignment: { ...dp, totalValue: 200 },
};
const sensitivity: SensitivityResponse = {
  removedHosts: main.hosts.filter((host) => ["a", "b"].includes(host.id)), before: report, after: afterReport,
  removedLinkIds: ["e1", "e2", "e3"], addedLinkIds: ["replacement"], costDelta: -21, valueDelta: -90,
  newlyIsolatedHosts: [{ id: "c", name: "Host C" }], droppedTasks: [{ task: main.tasks[0], reason: "Host removed by sensitivity" }],
  newlyAssignedTaskIds: ["t3"], noLongerAssignedTaskIds: ["t1"], movedTasks: [{ taskId: "t4", fromTeamId: "Beta", toTeamId: "Alpha" }],
};
const repair = {
  scenario: { ...main, links: main.links.filter((edge) => !["e1", "e2", "e3"].includes(edge.id)) },
  compromisedLinkIds: ["e1", "e2", "e3"], original: backbone,
  incremental: { ...backbone, chosenLinks: [main.links[3]], totalWeight: 20, operations: { comparisons: 2, findCalls: 2, unions: 1, edgesScanned: 1 }, medianNanos: 9000 },
  fullRecompute: { ...backbone, chosenLinks: [main.links[3]], totalWeight: 20, medianNanos: 18000 }, sameEdges: true, sameCost: true,
};

function responseFor(path: string, init?: RequestInit): unknown {
  const body = init?.body ? JSON.parse(String(init.body)) as { scenarioId?: string } : {};
  if (path === "/api/daa/scenarios") return catalog;
  if (path === "/api/daa/scenarios/load") return body.scenarioId === "compact" ? compact : main;
  if (path === "/api/daa/backbone") return { scenario: main, backbone };
  if (path === "/api/daa/backbone/repair") return repair;
  if (path === "/api/daa/assign") return { scenario: main, ageGrowthRate: 0.1, tasks, assignment: dp };
  if (path === "/api/daa/compare") return comparison;
  if (path === "/api/daa/pipeline") return report;
  if (path === "/api/daa/sensitivity") return sensitivity;
  if (path === "/api/daa/edge-cases") return { allPassed: true, results: [{ id: "disconnected", name: "Disconnected graph", expectation: "Exclude unreachable tasks", passed: true, report }] };
  throw new Error(`Unexpected API path ${path}`);
}

beforeEach(() => {
  vi.mocked(readJson).mockReset();
  vi.mocked(readJson).mockImplementation(async (path, init) => responseFor(path, init));
  useApp.setState({ auth: "in", user: { username: "admin", role: "Admin" } });
});
afterEach(cleanup);

async function loadScenario(button: string) {
  await waitFor(() => expect(screen.getByLabelText("Scenario")).toHaveValue("main"));
  fireEvent.click(screen.getByRole("button", { name: "Load scenario" }));
  await waitFor(() => expect(screen.getByRole("button", { name: button })).toBeEnabled());
}
async function runBackbone() {
  await loadScenario("Run backbone");
  fireEvent.click(screen.getByRole("button", { name: "Run backbone" }));
  await screen.findByRole("heading", { name: "Original backbone" });
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

describe("DAA adapters", () => {
  test("wraps readJson with exact selector bodies and never includes undefined linkIds", async () => {
    await daaApi.scenarios();
    expect(readJson).toHaveBeenLastCalledWith("/api/daa/scenarios");
    await daaApi.load("main");
    expect(readJson).toHaveBeenLastCalledWith("/api/daa/scenarios/load", { method: "POST", body: '{"scenarioId":"main"}' });
    for (const endpoint of ["backbone", "assign", "compare", "pipeline", "sensitivity"] as const) {
      await daaApi[endpoint]("main");
      expect(readJson).toHaveBeenLastCalledWith(`/api/daa/${endpoint}`, { method: "POST", body: '{"scenarioId":"main"}' });
    }
    await daaApi.repair("main");
    expect(readJson).toHaveBeenLastCalledWith("/api/daa/backbone/repair", { method: "POST", body: '{"scenarioId":"main"}' });
    await daaApi.repair("main", ["e1", "e2", "e3"]);
    expect(readJson).toHaveBeenLastCalledWith("/api/daa/backbone/repair", { method: "POST", body: '{"scenarioId":"main","linkIds":["e1","e2","e3"]}' });
    await daaApi.edgeCases();
    expect(readJson).toHaveBeenLastCalledWith("/api/daa/edge-cases", { method: "POST", body: "{}" });
  });
  test.each([[], ["e1", "e2"], ["e1", "e1", "e2"], ["e1", "e2", "missing"], ["e1", "e2", "e3", "e4"]].map((ids) => ({ ids })))("rejects invalid repair selection $ids", ({ ids }) => {
    expect(validRepairSelection(backbone.chosenLinks, ids)).toBe(false);
  });
  test("accepts exactly three distinct current chosen IDs", () => {
    expect(validRepairSelection(backbone.chosenLinks, ["e1", "e2", "e3"])).toBe(true);
  });
});

describe("standalone explicit SVG map", () => {
  test("renders only explicit edges with cost titles, chosen/compromised styles and isolated names", () => {
    const { container } = render(<ScenarioMap scenario={main} chosenLinkIds={["e1"]} compromisedLinkIds={["e2"]} />);
    const svg = screen.getByRole("img", { name: /Scenario topology/ });
    expect(svg.querySelectorAll("line")).toHaveLength(main.links.length);
    expect(svg.querySelectorAll("[data-edge-id]")).toHaveLength(main.links.length);
    expect(svg.querySelector('[data-edge-id="e1"]')).toHaveClass("is-chosen");
    expect(svg.querySelector('[data-edge-id="e2"]')).toHaveClass("is-compromised");
    expect(svg.querySelector('[data-edge-id="e5"]')).toHaveClass("is-compromised");
    expect(svg.querySelector('[data-edge-id="e1"] title')).toHaveTextContent("cost 10");
    expect(svg.querySelector('[data-host-id="orphan"] text')).toHaveTextContent("Isolated host");
    expect(container.querySelectorAll("canvas")).toHaveLength(0);
    expect(screen.getByRole("table", { name: "Explicit scenario links" }).querySelectorAll("tbody tr")).toHaveLength(5);
  });
  test("never synthesizes edges for a linkless scenario", () => {
    render(<ScenarioMap scenario={{ ...main, links: [] }} />);
    expect(screen.getByRole("img").querySelectorAll("line")).toHaveLength(0);
    expect(screen.getByRole("img").querySelectorAll("[data-host-id] text")).toHaveLength(main.hosts.length);
  });
  test("layout is stable when input host order changes", () => {
    const { rerender } = render(<ScenarioMap scenario={main} />);
    const coordinates = () => Array.from(screen.getByRole("img").querySelectorAll("[data-host-id] circle")).map((circle) => [circle.getAttribute("cx"), circle.getAttribute("cy")]);
    const before = coordinates();
    rerender(<ScenarioMap scenario={{ ...main, hosts: [...main.hosts].reverse() }} />);
    expect(coordinates()).toEqual(before);
  });
  test("200 hosts / 1000 links remain explicit with viewBox, titles and complete tables", () => {
    const hosts = Array.from({ length: 200 }, (_, index) => ({ id: `h${index}`, name: `Host ${index}`, riskScore: index % 100, criticality: 1 }));
    const links = Array.from({ length: 1000 }, (_, index) => ({ id: `l${index}`, sourceId: hosts[index % 200].id, targetId: hosts[(index + 1) % 200].id, cost: index, compromised: false }));
    render(<ScenarioMap scenario={{ ...main, socHostId: "h0", hosts, links }} />);
    const svg = screen.getByRole("img");
    expect(svg).toHaveAttribute("viewBox", "0 0 2400 2400");
    expect(svg.querySelectorAll("[data-edge-id]")).toHaveLength(1000);
    expect(svg.querySelectorAll("[data-edge-id] title")).toHaveLength(1000);
    expect(svg.querySelectorAll("[data-edge-id] text")).toHaveLength(0);
    expect(screen.getByRole("table", { name: "Scenario hosts" }).querySelectorAll("tbody tr")).toHaveLength(200);
    expect(screen.getByRole("table", { name: "Explicit scenario links" }).querySelectorAll("tbody tr")).toHaveLength(1000);
  });
});

describe("Reconnection", () => {
  test("0, 2, 3 and 4 selections enforce exactly three; default repair omits IDs", async () => {
    render(<Reconnection />);
    expect(screen.getByRole("button", { name: "Run backbone" })).toBeDisabled();
    await runBackbone();
    const selectedRepair = screen.getByRole("button", { name: "Repair selected 3 links" });
    expect(selectedRepair).toBeDisabled();
    fireEvent.click(screen.getByLabelText("Select link e1"));
    fireEvent.click(screen.getByLabelText("Select link e2"));
    expect(selectedRepair).toBeDisabled();
    fireEvent.click(screen.getByLabelText("Select link e3"));
    expect(selectedRepair).toBeEnabled();
    fireEvent.click(screen.getByLabelText("Select link e4"));
    expect(selectedRepair).toBeDisabled();
    fireEvent.click(screen.getByLabelText("Select link e4"));
    fireEvent.click(selectedRepair);
    await screen.findByRole("heading", { name: "Repair verification" });
    expect(readJson).toHaveBeenCalledWith("/api/daa/backbone/repair", { method: "POST", body: '{"scenarioId":"main","linkIds":["e1","e2","e3"]}' });
    expect(screen.getByRole("img").querySelectorAll("line")).toHaveLength(main.links.length);
    expect(main.links[0].compromised).toBe(false);
    expect(screen.getByText(/Compromised link IDs:/)).toHaveTextContent("e1, e2, e3");
    expect(screen.getByRole("heading", { name: "Incremental repair" })).toBeTruthy();
    expect(screen.getByRole("heading", { name: "Full recomputation" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Repair highest-cost 3 (server default)" }));
    await waitFor(() => expect(readJson).toHaveBeenLastCalledWith("/api/daa/backbone/repair", { method: "POST", body: '{"scenarioId":"main"}' }));
  });
  test("renders server-only counters and algorithm medians, not client stopwatch values", async () => {
    render(<Reconnection />);
    await runBackbone();
    expect(screen.getByText("123.000 µs")).toBeTruthy();
    expect(screen.getByText(/Server algorithm median:/)).toHaveTextContent("3 warmup / 9 measured runs (not HTTP latency)");
    expect(screen.getByText("Comparisons")).toBeTruthy();
    expect(screen.getByText("Find calls")).toBeTruthy();
    expect(screen.getByText(/Isolated \/ unreachable from SOC:/)).toHaveTextContent("Isolated host (orphan)");
  });
  test("scenario change invalidates a pending load, clears output, and permits a fresh load", async () => {
    const old = deferred<Scenario>();
    vi.mocked(readJson).mockImplementation(async (path, init) => path === "/api/daa/scenarios/load" && String(init?.body).includes("main") ? old.promise : responseFor(path, init));
    render(<Reconnection />);
    await waitFor(() => expect(screen.getByLabelText("Scenario")).toHaveValue("main"));
    fireEvent.click(screen.getByRole("button", { name: "Load scenario" }));
    expect(screen.getByRole("button", { name: "Load scenario" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Run backbone" })).toBeDisabled();
    expect(screen.getByRole("status")).toHaveTextContent("Loading scenario");
    fireEvent.change(screen.getByLabelText("Scenario"), { target: { value: "compact" } });
    fireEvent.click(screen.getByRole("button", { name: "Load scenario" }));
    await screen.findByRole("img");
    await act(async () => { old.resolve(main); await old.promise; });
    expect(screen.getByRole("img").querySelectorAll("[data-host-id]")).toHaveLength(1);
    expect(screen.getByRole("img").querySelector('[data-host-id="solo"]')).toBeTruthy();
    expect(screen.queryByRole("heading", { name: "Original backbone" })).toBeNull();
  });
  test("ignores stale run success after scenario changes", async () => {
    const old = deferred<{ scenario: Scenario; backbone: Backbone }>();
    vi.mocked(readJson).mockImplementation(async (path, init) => path === "/api/daa/backbone" ? old.promise : responseFor(path, init));
    render(<Reconnection />);
    await loadScenario("Run backbone");
    fireEvent.click(screen.getByRole("button", { name: "Run backbone" }));
    expect(screen.getByRole("button", { name: "Run backbone" })).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Scenario"), { target: { value: "compact" } });
    await act(async () => { old.resolve({ scenario: main, backbone }); await old.promise; });
    expect(screen.queryByRole("heading", { name: "Original backbone" })).toBeNull();
    expect(screen.queryByRole("img")).toBeNull();
    expect(screen.getByRole("button", { name: "Run backbone" })).toBeDisabled();
  });
  test("stale failures cannot replace the new scenario's state or error", async () => {
    const old = deferred<{ scenario: Scenario; backbone: Backbone }>();
    vi.mocked(readJson).mockImplementation(async (path, init) => path === "/api/daa/backbone" ? old.promise : responseFor(path, init));
    render(<Reconnection />);
    await loadScenario("Run backbone");
    fireEvent.click(screen.getByRole("button", { name: "Run backbone" }));
    fireEvent.change(screen.getByLabelText("Scenario"), { target: { value: "compact" } });
    fireEvent.click(screen.getByRole("button", { name: "Load scenario" }));
    await screen.findByRole("img");
    await act(async () => { old.reject(new Error("Old scenario failure")); await old.promise.catch(() => undefined); });
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.getByRole("img").querySelector('[data-host-id="solo"]')).toBeTruthy();
    expect(screen.getByRole("button", { name: "Run backbone" })).toBeEnabled();
  });
  test("fewer than three chosen links disables both repair actions", async () => {
    vi.mocked(readJson).mockImplementation(async (path, init) => path === "/api/daa/backbone" ? { scenario: main, backbone: { ...backbone, chosenLinks: main.links.slice(0, 2) } } : responseFor(path, init));
    render(<Reconnection />);
    await runBackbone();
    expect(screen.getByRole("button", { name: "Repair selected 3 links" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Repair highest-cost 3 (server default)" })).toBeDisabled();
    expect(screen.getByText(/fewer than 3 chosen links/)).toBeTruthy();
  });
  test("reloading clears repair selection and prior outputs", async () => {
    render(<Reconnection />);
    await runBackbone();
    for (const id of ["e1", "e2", "e3"]) fireEvent.click(screen.getByLabelText(`Select link ${id}`));
    expect(screen.getByRole("button", { name: "Repair selected 3 links" })).toBeEnabled();
    fireEvent.click(screen.getByRole("button", { name: "Load scenario" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Run backbone" })).toBeEnabled());
    expect(screen.queryByRole("heading", { name: "Original backbone" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Run backbone" }));
    await screen.findByRole("heading", { name: "Original backbone" });
    expect(screen.getByRole("button", { name: "Repair selected 3 links" })).toBeDisabled();
    expect(screen.getByLabelText("Select link e1")).not.toBeChecked();
  });
  test("failed runs show errors without inventing output and can be retried", async () => {
    render(<Reconnection />);
    await loadScenario("Run backbone");
    vi.mocked(readJson).mockRejectedValueOnce(new HttpStatusError(403));
    fireEvent.click(screen.getByRole("button", { name: "Run backbone" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("require Admin authorization");
    expect(screen.queryByRole("heading", { name: "Original backbone" })).toBeNull();
    expect(screen.getByRole("button", { name: "Run backbone" })).toBeEnabled();
    fireEvent.click(screen.getByRole("button", { name: "Run backbone" }));
    await screen.findByRole("heading", { name: "Original backbone" });
    expect(screen.queryByRole("alert")).toBeNull();
  });
  test("empty catalog disables load and run", async () => {
    vi.mocked(readJson).mockResolvedValue([]);
    render(<Reconnection />);
    await screen.findByText("No scenarios available.");
    expect(screen.getByRole("button", { name: "Load scenario" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Run backbone" })).toBeDisabled();
  });
  test("catalog failure has a working retry", async () => {
    vi.mocked(readJson).mockRejectedValueOnce(new Error("Catalog offline"));
    render(<Reconnection />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Catalog offline");
    fireEvent.click(screen.getByRole("button", { name: "Retry scenarios" }));
    await waitFor(() => expect(screen.getByLabelText("Scenario")).toHaveValue("main"));
    expect(screen.queryByRole("alert")).toBeNull();
  });
});

describe("Mitigation", () => {
  test("shows base/age/effort/adjusted tasks and distinct assigned, excluded, unassigned groups", async () => {
    render(<Mitigation />);
    await loadScenario("Run assignment");
    expect(screen.getByRole("table", { name: "Scenario tasks" })).toBeTruthy();
    expect(screen.queryByRole("table", { name: "Adjusted tasks" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Run assignment" }));
    await screen.findByRole("heading", { name: "Team allocation" });
    expect(screen.getByText(/Linear age growth:/)).toHaveTextContent("round(baseValue * (1 + rate * daysUnpatched))");
    expect(screen.getByText(/Linear age growth:/)).toHaveTextContent("rounded once");
    expect(screen.getByRole("table", { name: "Adjusted tasks" }).querySelectorAll("tbody tr")).toHaveLength(4);
    for (const name of ["Assigned", "Excluded", "Unassigned"]) expect(screen.getByRole("heading", { name })).toBeTruthy();
    expect(screen.getByText(/t2 · Isolated host: Unreachable from SOC/)).toBeTruthy();
    expect(screen.getByText(/t3 · Host C: Effort exceeds team capacity/)).toBeTruthy();
    expect(screen.getByText("Alpha · 4 / 8 hours used")).toBeTruthy();
    expect(screen.getByText("2.500 ms")).toBeTruthy();
  });
  test("three solver comparisons display returned values, gaps, state cells and operation counts", async () => {
    render(<Mitigation />);
    await loadScenario("Compare 3 solvers");
    fireEvent.click(screen.getByRole("button", { name: "Compare 3 solvers" }));
    const table = await screen.findByRole("table", { name: "Solver value and work comparison" });
    const rows = within(table).getAllByRole("row");
    expect(rows).toHaveLength(4);
    expect(rows[1]).toHaveTextContent("DP2900450789");
    expect(rows[2]).toHaveTextContent("GREEDY20090412");
    expect(rows[3]).toHaveTextContent("BRUTE_FORCE290081162");
    expect(screen.getByRole("table", { name: "Team allocation comparison" })).toBeTruthy();
    expect(screen.getByRole("heading", { name: "BRUTE_FORCE allocation" })).toBeTruthy();
  });
  test("server brute-force limit is a notice, not an invented allocation or timing", async () => {
    vi.mocked(readJson).mockImplementation(async (path, init) => path === "/api/daa/compare" ? { ...comparison, bruteForce: null, bruteForceError: "Task count 13 exceeds limit 12" } : responseFor(path, init));
    render(<Mitigation />);
    await loadScenario("Compare 3 solvers");
    fireEvent.click(screen.getByRole("button", { name: "Compare 3 solvers" }));
    expect(await screen.findByText(/Brute force is limited to n ≤ 12 tasks/)).toHaveTextContent("Task count 13 exceeds limit 12");
    expect(screen.getByRole("status")).toHaveTextContent("Task count 13 exceeds limit 12");
    expect(screen.queryByRole("heading", { name: "BRUTE_FORCE allocation" })).toBeNull();
    const row = screen.getByRole("table", { name: "Solver value and work comparison" }).querySelector("tbody tr:last-child")!;
    expect(row).toHaveTextContent("Unavailable");
    expect(row).not.toHaveTextContent("Server algorithm median");
  });
});

describe("Pipeline", () => {
  test("pipeline, sensitivity and edge cases render server reports, named drops and movement", async () => {
    render(<Pipeline />);
    await loadScenario("Run pipeline");
    expect(screen.queryByText("66 → 45")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Run pipeline" }));
    expect(await screen.findByRole("heading", { name: "Pipeline report" })).toBeTruthy();
    expect(screen.getAllByText(/t2 · Isolated host: Host disconnected/).length).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole("button", { name: "Run top-2 host sensitivity" }));
    await screen.findByRole("heading", { name: "Top-2 sensitivity changes" });
    expect(screen.getByText("66 → 45")).toBeTruthy();
    expect(screen.getByText("290 → 200")).toBeTruthy();
    expect(screen.getByText("-21")).toBeTruthy();
    expect(screen.getByText("-90")).toBeTruthy();
    expect(screen.getByText(/Removed hosts:/)).toHaveTextContent("Host A (a), Host B (b)");
    expect(screen.getByText(/Newly isolated hosts:/)).toHaveTextContent("Host C (c)");
    expect(screen.getByText(/Newly assigned tasks:/)).toHaveTextContent("t3 · Host C");
    expect(screen.getByText(/No longer assigned tasks:/)).toHaveTextContent("t1 · Host A");
    expect(screen.getByText("t4 · Host D: Beta → Alpha")).toBeTruthy();
    expect(screen.getByText("t1 · Host A: Host removed by sensitivity")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Run edge cases" }));
    await screen.findByRole("heading", { name: "Edge-case verification" });
    expect(screen.getByText("All edge cases passed.")).toBeTruthy();
    const cases = screen.getByRole("table", { name: "Edge-case expectations and results" });
    expect(cases).toHaveTextContent("Disconnected graph (disconnected)");
    expect(cases).toHaveTextContent("Exclude unreachable tasks");
    expect(cases).toHaveTextContent("Passed");
    expect(cases).toHaveTextContent("t2 · Isolated host: Host disconnected");
  });
  test("sensitivity deltas are never hardcoded to the worked-example numbers", async () => {
    vi.mocked(readJson).mockImplementation(async (path, init) => path === "/api/daa/sensitivity" ? { ...sensitivity, before: { ...report, backbone: { ...backbone, totalWeight: 81 }, assignment: { ...dp, totalValue: 401 } }, after: { ...afterReport, backbone: { ...backbone, totalWeight: 72 }, assignment: { ...dp, totalValue: 333 } }, costDelta: -9, valueDelta: -68 } : responseFor(path, init));
    render(<Pipeline />);
    await loadScenario("Run top-2 host sensitivity");
    fireEvent.click(screen.getByRole("button", { name: "Run top-2 host sensitivity" }));
    expect(await screen.findByText("81 → 72")).toBeTruthy();
    expect(screen.getByText("401 → 333")).toBeTruthy();
    expect(screen.getByText("-9")).toBeTruthy();
    expect(screen.getByText("-68")).toBeTruthy();
    expect(screen.queryByText("66 → 45")).toBeNull();
  });
  test("edge cases can run without loading a scenario and report failures honestly", async () => {
    vi.mocked(readJson).mockImplementation(async (path, init) => path === "/api/daa/edge-cases" ? { allPassed: false, results: [{ id: "failed", name: "Capacity case", passed: false, expectation: "Keep within capacity", report }] } : responseFor(path, init));
    render(<Pipeline />);
    expect(screen.getByRole("button", { name: "Run pipeline" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Run edge cases" }));
    expect(await screen.findByText("One or more edge cases failed.")).toBeTruthy();
    expect(screen.getByRole("table", { name: "Edge-case expectations and results" })).toHaveTextContent("Failed");
    expect(readJson).toHaveBeenCalledWith("/api/daa/edge-cases", { method: "POST", body: "{}" });
  });
  test("all workspace operations leave live Zustand and traffic scenario state untouched", async () => {
    const before = useApp.getState();
    const traffic = getScenario();
    const { unmount } = render(<Reconnection />);
    await runBackbone();
    fireEvent.click(screen.getByRole("button", { name: "Repair highest-cost 3 (server default)" }));
    await screen.findByRole("heading", { name: "Repair verification" });
    unmount();
    const mitigation = render(<Mitigation />);
    await loadScenario("Compare 3 solvers");
    fireEvent.click(screen.getByRole("button", { name: "Compare 3 solvers" }));
    await screen.findByRole("heading", { name: "Solver comparison" });
    mitigation.unmount();
    render(<Pipeline />);
    await loadScenario("Run pipeline");
    fireEvent.click(screen.getByRole("button", { name: "Run top-2 host sensitivity" }));
    await screen.findByRole("heading", { name: "Top-2 sensitivity changes" });
    fireEvent.click(screen.getByRole("button", { name: "Run edge cases" }));
    await screen.findByRole("heading", { name: "Edge-case verification" });
    expect(useApp.getState()).toBe(before);
    expect(getScenario()).toBe(traffic);
  });
});
