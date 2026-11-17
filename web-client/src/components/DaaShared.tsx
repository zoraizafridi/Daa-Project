import { useEffect, useRef, useState } from "react";
import { HttpStatusError } from "../api";
import { daaApi, type AdjustedTask, type Assignment, type Backbone, type Scenario, type ScenarioEdge, type ScenarioSummary } from "../daa";
import { EmptyState, Panel } from "./ui";

function errorMessage(error: unknown): string {
  if (error instanceof HttpStatusError) {
    if (error.status === 403) return "Access denied. These operations require Admin authorization on the server.";
    if (error.status === 401) return "Your session has expired. Sign in again.";
    if (error.status === 400) return "Invalid request. Reload the scenario and check your selection.";
    return `Server request failed (${error.status}). Try again.`;
  }
  return error instanceof Error ? error.message : "Request failed. Try again.";
}

// Page-local workspace: never writes to the live network, traffic or planner stores.
export function useDaaWorkspace<T>() {
  const [scenarios, setScenarios] = useState<ScenarioSummary[]>([]);
  const [selectedId, setSelectedId] = useState("");
  const [scenario, setScenario] = useState<Scenario | null>(null);
  const [output, setOutput] = useState<T | null>(null);
  const [busy, setBusy] = useState("");
  const [catalogBusy, setCatalogBusy] = useState(true);
  const [catalogError, setCatalogError] = useState("");
  const [error, setError] = useState("");
  const [catalogRetry, setCatalogRetry] = useState(0);
  const epoch = useRef(0);
  const pending = useRef(false);
  const alive = useRef(true);

  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; epoch.current++; };
  }, []);

  useEffect(() => {
    let current = true;
    setCatalogBusy(true);
    setCatalogError("");
    daaApi.scenarios().then((rows) => {
      if (!current) return;
      setScenarios(rows);
      setSelectedId((id) => rows.some((row) => row.id === id) ? id : rows.find((row) => row.id === "main")?.id ?? rows[0]?.id ?? "");
    }).catch((cause: unknown) => { if (current) setCatalogError(errorMessage(cause)); })
      .finally(() => { if (current) setCatalogBusy(false); });
    return () => { current = false; };
  }, [catalogRetry]);

  function select(id: string) {
    epoch.current++;
    pending.current = false;
    setSelectedId(id);
    setScenario(null);
    setOutput(null);
    setBusy("");
    setError("");
  }

  async function load() {
    if (pending.current || !scenarios.some((row) => row.id === selectedId)) return;
    const ticket = ++epoch.current;
    pending.current = true;
    setBusy("Loading scenario");
    setError("");
    setOutput(null);
    setScenario(null);
    try {
      const value = await daaApi.load(selectedId);
      if (alive.current && ticket === epoch.current) setScenario(value);
    } catch (cause) {
      if (alive.current && ticket === epoch.current) setError(errorMessage(cause));
    } finally {
      if (alive.current && ticket === epoch.current) { pending.current = false; setBusy(""); }
    }
  }

  async function run<R>(label: string, job: (id: string) => Promise<R>, accept: (value: R, previous: T | null) => T, requiresScenario = true) {
    if (pending.current || (requiresScenario && (!scenario || scenario.id !== selectedId))) return;
    const ticket = epoch.current;
    pending.current = true;
    setBusy(label);
    setError("");
    try {
      const value = await job(selectedId);
      if (alive.current && ticket === epoch.current) setOutput((previous) => accept(value, previous));
    } catch (cause) {
      if (alive.current && ticket === epoch.current) setError(errorMessage(cause));
    } finally {
      if (alive.current && ticket === epoch.current) { pending.current = false; setBusy(""); }
    }
  }

  return { scenarios, selectedId, scenario, output, busy, catalogBusy, catalogError, error, select, load, run, retryCatalog: () => setCatalogRetry((value) => value + 1) };
}

export function ScenarioPicker({ scenarios, selectedId, onSelect, onLoad, busy, catalogBusy, catalogError, error, onRetry }: {
  scenarios: ScenarioSummary[]; selectedId: string; onSelect: (id: string) => void; onLoad: () => void;
  busy: string; catalogBusy: boolean; catalogError: string; error: string; onRetry: () => void;
}) {
  const row = scenarios.find((item) => item.id === selectedId);
  return <Panel title="Scenario" subtitle="Offline algorithm workspace · does not change the live network">
    <div className="daa-controls">
      <label>Scenario<select value={selectedId} onChange={(event) => onSelect(event.target.value)} disabled={catalogBusy || !scenarios.length}>
        {!scenarios.length ? <option value="">No scenarios</option> : null}
        {scenarios.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
      </select></label>
      <button type="button" onClick={onLoad} disabled={!!busy || catalogBusy || !row}>Load scenario</button>
    </div>
    {row ? <p>{row.hostCount} hosts · {row.linkCount} links · {row.teamCount} teams · {row.taskCount} tasks</p> : null}
    {catalogBusy ? <p role="status">Loading scenarios…</p> : !scenarios.length && !catalogError ? <EmptyState>No scenarios available.</EmptyState> : null}
    {catalogError ? <div role="alert"><p>{catalogError}</p><button type="button" onClick={onRetry} disabled={catalogBusy || !!busy}>Retry scenarios</button></div> : null}
    {busy ? <p role="status">{busy}…</p> : null}
    {error ? <p role="alert">{error}</p> : null}
  </Panel>;
}

export function ServerTiming({ value }: { value: { medianNanos: number; warmupRuns: number; measuredRuns: number } }) {
  const nanos = value.medianNanos;
  const time = nanos >= 1_000_000 ? `${(nanos / 1_000_000).toFixed(3)} ms` : `${(nanos / 1_000).toFixed(3)} µs`;
  return <p className="daa-timing">Server algorithm median: <strong>{time}</strong> · {value.warmupRuns} warmup / {value.measuredRuns} measured runs (not HTTP latency)</p>;
}

export function BackboneOutput({ title, value }: { title: string; value: Backbone }) {
  return <Panel title={title} className="daa-output">
    <p>Total cost: <strong>{value.totalWeight}</strong> · {value.connected ? "Connected" : "Disconnected"} · {value.chosenLinks.length} chosen links</p>
    <dl className="daa-metrics">
      <div><dt>Comparisons</dt><dd>{value.operations.comparisons}</dd></div>
      <div><dt>Find calls</dt><dd>{value.operations.findCalls}</dd></div>
      <div><dt>Unions</dt><dd>{value.operations.unions}</dd></div>
      <div><dt>Edges scanned</dt><dd>{value.operations.edgesScanned}</dd></div>
    </dl>
    <ServerTiming value={value} />
    <p>Isolated / unreachable from SOC: {value.unreachable.length ? value.unreachable.map((host) => `${host.name} (${host.id})`).join(", ") : "None"}</p>
  </Panel>;
}

export function EdgeTable({ title, edges, scenario, selected, onToggle, disabled = false }: {
  title: string; edges: ScenarioEdge[]; scenario: Scenario; selected?: string[]; onToggle?: (id: string) => void; disabled?: boolean;
}) {
  const names = new Map(scenario.hosts.map((host) => [host.id, host.name]));
  return <div className="daa-table-wrap">
    <table><caption>{title}</caption><thead><tr>{onToggle ? <th scope="col">Compromise</th> : null}<th scope="col">ID</th><th scope="col">Source</th><th scope="col">Target</th><th scope="col">Cost</th><th scope="col">State</th></tr></thead>
      <tbody>{edges.map((edge) => <tr key={edge.id}>{onToggle ? <td><input type="checkbox" aria-label={`Select link ${edge.id}`} checked={selected?.includes(edge.id) ?? false} disabled={disabled} onChange={() => onToggle(edge.id)} /></td> : null}<td>{edge.id}</td><td>{names.get(edge.sourceId) ?? edge.sourceId} ({edge.sourceId})</td><td>{names.get(edge.targetId) ?? edge.targetId} ({edge.targetId})</td><td>{edge.cost}</td><td>{edge.compromised ? "Compromised" : "Available"}</td></tr>)}</tbody>
    </table>{!edges.length ? <EmptyState>No links.</EmptyState> : null}
  </div>;
}

export function TaskTable({ tasks, rate }: { tasks: AdjustedTask[]; rate: number }) {
  return <div className="daa-table-wrap">
    <p>Linear age growth: adjustedValue = round(baseValue * (1 + rate * daysUnpatched)), rounded once. Server rate: {rate}. Not decay or exponential growth.</p>
    <table><caption>Adjusted tasks</caption><thead><tr><th scope="col">Task / host</th><th scope="col">Base value</th><th scope="col">Days unpatched</th><th scope="col">Effort (hours)</th><th scope="col">Adjusted value</th></tr></thead><tbody>
      {tasks.map((task) => <tr key={task.id}><td>{task.id} · {task.hostName} ({task.hostId})</td><td>{task.baseValue}</td><td>{task.daysUnpatched}</td><td>{task.effortHours}</td><td>{task.adjustedValue}</td></tr>)}
    </tbody></table>{!tasks.length ? <EmptyState>No adjusted tasks.</EmptyState> : null}
  </div>;
}

export function AssignmentOutput({ value, title = "Team allocation" }: { value: Assignment; title?: string }) {
  return <Panel title={title} className="daa-output">
    <p>{value.solver} · Total value: <strong>{value.totalValue}</strong> · States: {value.stateCount} · Transition operations: {value.transitionOps}</p>
    <ServerTiming value={value} />
    <h3>Assigned</h3>
    {value.teams.length ? value.teams.map((team) => <div key={team.id} className="daa-team"><h4>{team.id} · {team.usedHours} / {team.capacity} hours used</h4>
      {team.tasks.length ? <ul>{team.tasks.map((task) => <li key={task.id}>{task.id} · {task.hostName} ({task.hostId}) · {task.effortHours} hours · value {task.adjustedValue}</li>)}</ul> : <EmptyState>No tasks assigned to this team.</EmptyState>}
    </div>) : <EmptyState>No teams available.</EmptyState>}
    <h3>Excluded</h3>{value.excluded.length ? <ul>{value.excluded.map(({ task, reason }) => <li key={task.id}>{task.id} · {task.hostName}: {reason}</li>)}</ul> : <EmptyState>No excluded tasks.</EmptyState>}
    <h3>Unassigned</h3>{value.unassigned.length ? <ul>{value.unassigned.map(({ task, reason }) => <li key={task.id}>{task.id} · {task.hostName}: {reason}</li>)}</ul> : <EmptyState>No unassigned tasks.</EmptyState>}
  </Panel>;
}

