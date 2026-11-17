import { daaApi, type AssignResponse, type CompareResponse } from "../daa";
import { AssignmentOutput, ScenarioPicker, ServerTiming, TaskTable, useDaaWorkspace } from "../components/DaaShared";
import { EmptyState, Page, Panel } from "../components/ui";

type MitigationOutput = { kind: "assign"; value: AssignResponse } | { kind: "compare"; value: CompareResponse };

export function Mitigation() {
  const work = useDaaWorkspace<MitigationOutput>();
  const ready = !!work.scenario && !work.busy;
  const result = work.output?.value;
  const comparison = work.output?.kind === "compare" ? work.output.value : null;
  const solvers = comparison ? [comparison.dp, comparison.greedy, comparison.bruteForce] : [];

  return <Page title="Mitigation" description="Allocate limited team capacity to age-adjusted mitigation tasks and compare three solvers.">
    <div className="daa-page" aria-busy={!!work.busy}>
      <ScenarioPicker scenarios={work.scenarios} selectedId={work.selectedId} onSelect={work.select} onLoad={work.load} busy={work.busy} catalogBusy={work.catalogBusy} catalogError={work.catalogError} error={work.error} onRetry={work.retryCatalog} />
      <div className="daa-controls">
        <button type="button" disabled={!ready} onClick={() => void work.run("Assigning tasks", daaApi.assign, (value) => ({ kind: "assign", value }))}>Run assignment</button>
        <button type="button" disabled={!ready} onClick={() => void work.run("Comparing solvers", daaApi.compare, (value) => ({ kind: "compare", value }))}>Compare 3 solvers</button>
      </div>
      {work.scenario ? <Panel title={work.scenario.name} subtitle="Input task values and team capacities">
        <div className="daa-table-wrap"><table><caption>Scenario tasks</caption><thead><tr><th scope="col">Task ID</th><th scope="col">Host</th><th scope="col">Base value</th><th scope="col">Days unpatched</th><th scope="col">Effort (hours)</th></tr></thead><tbody>
          {work.scenario.tasks.map((task) => <tr key={task.id}><td>{task.id}</td><td>{task.hostName}</td><td>{task.baseValue}</td><td>{task.daysUnpatched}</td><td>{task.effortHours}</td></tr>)}
        </tbody></table>{!work.scenario.tasks.length ? <EmptyState>No tasks in this scenario.</EmptyState> : null}</div>
        <div className="daa-table-wrap"><table><caption>Available teams</caption><thead><tr><th scope="col">Team</th><th scope="col">Capacity (hours)</th></tr></thead><tbody>{work.scenario.teams.map((team) => <tr key={team.id}><td>{team.id}</td><td>{team.capacity}</td></tr>)}</tbody></table>{!work.scenario.teams.length ? <EmptyState>No teams in this scenario.</EmptyState> : null}</div>
      </Panel> : <EmptyState>Load a scenario to view tasks and team capacities.</EmptyState>}
      {result ? <Panel title="Age-adjusted values"><TaskTable tasks={result.tasks} rate={result.ageGrowthRate} /></Panel> : <EmptyState>Adjusted values and allocations appear only after a server run.</EmptyState>}
      {work.output?.kind === "assign" ? <AssignmentOutput value={work.output.value.assignment} /> : null}
      {comparison ? <>
        <Panel title="Solver comparison" subtitle="Value gaps are DP total minus each solver total, computed from server results.">
          <div className="daa-table-wrap"><table><caption>Solver value and work comparison</caption><thead><tr><th scope="col">Solver</th><th scope="col">Total value</th><th scope="col">Gap to DP</th><th scope="col">States / table cells</th><th scope="col">Transition operations</th><th scope="col">Server algorithm timing</th></tr></thead><tbody>
            {solvers.map((solver, index) => solver ? <tr key={solver.solver}><th scope="row">{solver.solver}</th><td>{solver.totalValue}</td><td>{comparison.dp.totalValue - solver.totalValue}</td><td>{solver.stateCount}</td><td>{solver.transitionOps}</td><td><ServerTiming value={solver} /></td></tr> : <tr key={`unavailable-${index}`}><th scope="row">BRUTE_FORCE</th><td colSpan={5}>Unavailable: {comparison.bruteForceError ?? "No brute-force result returned by server."}</td></tr>)}
          </tbody></table></div>
          {!comparison.bruteForce ? <p role="status">Brute force is limited to n ≤ 12 tasks. Server notice: {comparison.bruteForceError ?? "Brute-force result unavailable."}</p> : null}
          <div className="daa-table-wrap"><table><caption>Team allocation comparison</caption><thead><tr><th scope="col">Team</th><th scope="col">DP task IDs</th><th scope="col">GREEDY task IDs</th><th scope="col">BRUTE_FORCE task IDs</th></tr></thead><tbody>
            {comparison.scenario.teams.map((team) => <tr key={team.id}><th scope="row">{team.id}</th>{solvers.map((solver, index) => <td key={index}>{solver ? solver.teams.find((row) => row.id === team.id)?.tasks.map((task) => task.id).join(", ") || "None" : "Unavailable"}</td>)}</tr>)}
          </tbody></table></div>
        </Panel>
        <div className="daa-solver-grid">{solvers.map((solver) => solver ? <AssignmentOutput key={solver.solver} title={`${solver.solver} allocation`} value={solver} /> : null)}</div>
      </> : null}
    </div>
  </Page>;
}
