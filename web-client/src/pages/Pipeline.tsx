import { daaApi, type DroppedTask, type EdgeCasesResponse, type PipelineReport, type SensitivityResponse } from "../daa";
import { AssignmentOutput, BackboneOutput, ScenarioPicker, TaskTable, useDaaWorkspace } from "../components/DaaShared";
import { ScenarioMap } from "../components/ScenarioMap";
import { EmptyState, Page, Panel } from "../components/ui";

interface PipelineOutput { report?: PipelineReport; sensitivity?: SensitivityResponse; edgeCases?: EdgeCasesResponse }

function DroppedTasks({ tasks }: { tasks: DroppedTask[] }) {
  return <div><h3>Dropped tasks</h3>{tasks.length ? <ul>{tasks.map(({ task, reason }) => <li key={task.id}>{task.id} · {task.hostName}: {reason}</li>)}</ul> : <EmptyState>No dropped tasks.</EmptyState>}</div>;
}

function ReportOutput({ report, title }: { report: PipelineReport; title: string }) {
  const activeScenario = { ...report.scenario, hosts: report.activeHosts, links: report.activeLinks };
  return <section className="daa-report" aria-label={title}>
    <h2>{title}</h2>
    <p>{report.activeHosts.length} active hosts · {report.activeLinks.length} active links · Backbone cost: {report.backbone.totalWeight} · Assigned value: {report.assignment.totalValue}</p>
    <Panel title="Active scenario topology"><ScenarioMap scenario={activeScenario} title={`${title} topology`} chosenLinkIds={report.backbone.chosenLinks.map((edge) => edge.id)} isolatedHostIds={report.backbone.unreachable.map((host) => host.id)} /></Panel>
    <BackboneOutput title={`${title} backbone`} value={report.backbone} />
    <Panel title={`${title} tasks`}><TaskTable tasks={report.tasks} rate={report.ageGrowthRate} /><DroppedTasks tasks={report.droppedTasks} /></Panel>
    <AssignmentOutput title={`${title} allocation`} value={report.assignment} />
  </section>;
}

export function Pipeline() {
  const work = useDaaWorkspace<PipelineOutput>();
  const ready = !!work.scenario && !work.busy;
  const sensitivity = work.output?.sensitivity;
  const edgeCases = work.output?.edgeCases;
  const taskName = (id: string) => {
    const task = sensitivity?.before.tasks.find((item) => item.id === id) ?? sensitivity?.after.tasks.find((item) => item.id === id);
    return task ? `${id} · ${task.hostName}` : id;
  };

  return <Page title="Pipeline" description="Run reconnection and mitigation together, inspect top-two-host sensitivity, and verify server edge cases.">
    <div className="daa-page" aria-busy={!!work.busy}>
      <ScenarioPicker scenarios={work.scenarios} selectedId={work.selectedId} onSelect={work.select} onLoad={work.load} busy={work.busy} catalogBusy={work.catalogBusy} catalogError={work.catalogError} error={work.error} onRetry={work.retryCatalog} />
      <Panel title="Worked example" subtitle="The main scenario is selected by default when available. Load it, then run the pipeline. All costs, values and differences below come from server reports.">
        <div className="daa-controls">
          <button type="button" disabled={!ready} onClick={() => void work.run("Running pipeline", daaApi.pipeline, (report, previous) => ({ ...previous, report, sensitivity: undefined }))}>Run pipeline</button>
          <button type="button" disabled={!ready} onClick={() => void work.run("Running top-2 sensitivity", daaApi.sensitivity, (value, previous) => ({ ...previous, sensitivity: value }))}>Run top-2 host sensitivity</button>
          <button type="button" disabled={!!work.busy} onClick={() => void work.run("Running edge cases", daaApi.edgeCases, (value, previous) => ({ ...previous, edgeCases: value }), false)}>Run edge cases</button>
        </div>
      </Panel>
      {work.scenario && !work.output?.report && !sensitivity ? <Panel title={work.scenario.name}><ScenarioMap scenario={work.scenario} /></Panel> : null}
      {!work.scenario ? <EmptyState>Load a scenario to run the pipeline or sensitivity. Edge cases can run independently.</EmptyState> : null}
      {work.output?.report && !sensitivity ? <ReportOutput report={work.output.report} title="Pipeline report" /> : null}
      {sensitivity ? <>
        <Panel title="Top-2 sensitivity changes">
          <p>Removed hosts: {sensitivity.removedHosts.map((host) => `${host.name} (${host.id})`).join(", ") || "None"}</p>
          <dl className="daa-metrics">
            <div><dt>Backbone cost (before → after)</dt><dd>{sensitivity.before.backbone.totalWeight} → {sensitivity.after.backbone.totalWeight}</dd></div>
            <div><dt>Assigned value (before → after)</dt><dd>{sensitivity.before.assignment.totalValue} → {sensitivity.after.assignment.totalValue}</dd></div>
            <div><dt>Cost delta (server)</dt><dd>{sensitivity.costDelta}</dd></div>
            <div><dt>Value delta (server)</dt><dd>{sensitivity.valueDelta}</dd></div>
          </dl>
          <p>Removed link IDs: {sensitivity.removedLinkIds.join(", ") || "None"}</p>
          <p>Added link IDs: {sensitivity.addedLinkIds.join(", ") || "None"}</p>
          <p>Newly isolated hosts: {sensitivity.newlyIsolatedHosts.map((host) => `${host.name} (${host.id})`).join(", ") || "None"}</p>
          <p>Newly assigned tasks: {sensitivity.newlyAssignedTaskIds.map(taskName).join(", ") || "None"}</p>
          <p>No longer assigned tasks: {sensitivity.noLongerAssignedTaskIds.map(taskName).join(", ") || "None"}</p>
          <h3>Moved tasks</h3>{sensitivity.movedTasks.length ? <ul>{sensitivity.movedTasks.map((move) => <li key={move.taskId}>{taskName(move.taskId)}: {move.fromTeamId} → {move.toTeamId}</li>)}</ul> : <EmptyState>No tasks moved between teams.</EmptyState>}
          <DroppedTasks tasks={sensitivity.droppedTasks} />
        </Panel>
        <div className="daa-grid"><ReportOutput report={sensitivity.before} title="Before sensitivity" /><ReportOutput report={sensitivity.after} title="After sensitivity" /></div>
      </> : null}
      {edgeCases ? <Panel title="Edge-case verification">
        <p role="status">{edgeCases.allPassed ? "All edge cases passed." : "One or more edge cases failed."}</p>
        <div className="daa-table-wrap"><table><caption>Edge-case expectations and results</caption><thead><tr><th scope="col">Case</th><th scope="col">Expected behavior</th><th scope="col">Result</th><th scope="col">Cost / value</th><th scope="col">Reasons</th></tr></thead><tbody>
          {edgeCases.results.map((row) => <tr key={row.id}><th scope="row">{row.name} ({row.id})</th><td>{row.expectation}</td><td>{row.passed ? "Passed" : "Failed"}</td><td>{row.report.backbone.totalWeight} / {row.report.assignment.totalValue}</td><td>{[...row.report.droppedTasks, ...row.report.assignment.excluded, ...row.report.assignment.unassigned].map(({ task, reason }) => `${task.id} · ${task.hostName}: ${reason}`).join("; ") || "No excluded, unassigned or dropped tasks."}</td></tr>)}
        </tbody></table></div>
        {!edgeCases.results.length ? <EmptyState>No edge-case reports returned.</EmptyState> : null}
        {edgeCases.results.map((row) => <details key={row.id}><summary>{row.name} · {row.passed ? "Passed" : "Failed"} · inspect report</summary><ReportOutput report={row.report} title={`${row.name} report`} /></details>)}
      </Panel> : !work.output?.report && !sensitivity ? <EmptyState>No algorithm outputs yet.</EmptyState> : null}
    </div>
  </Page>;
}
