import { useState } from "react";
import { daaApi, validRepairSelection, type Backbone, type BackboneResponse, type RepairResponse } from "../daa";
import { BackboneOutput, EdgeTable, ScenarioPicker, useDaaWorkspace } from "../components/DaaShared";
import { ScenarioMap } from "../components/ScenarioMap";
import { EmptyState, Page, Panel } from "../components/ui";

interface ReconnectionOutput { original: BackboneResponse; repair: RepairResponse | null }

export function Reconnection() {
  const work = useDaaWorkspace<ReconnectionOutput>();
  const [selection, setSelection] = useState<{ backbone: Backbone | null; ids: string[] }>({ backbone: null, ids: [] });
  const original = work.output?.original;
  const repair = work.output?.repair;
  const chosen = original?.backbone.chosenLinks ?? [];
  const selected = original?.backbone === selection.backbone ? selection.ids : [];
  const valid = validRepairSelection(chosen, selected);
  const ready = !!work.scenario && !work.busy;

  function toggle(id: string) {
    if (!original || work.busy) return;
    setSelection({ backbone: original.backbone, ids: selected.includes(id) ? selected.filter((item) => item !== id) : [...selected, id] });
  }
  function runRepair(ids?: string[]) {
    if (!original || work.busy || (ids !== undefined && !validRepairSelection(chosen, ids)) || chosen.length < 3) return;
    void work.run("Repairing backbone", (id) => daaApi.repair(id, ids), (value) => ({ original, repair: value }));
  }

  return <Page title="Reconnection" description="Build a minimum-cost backbone and compare incremental repair with full recomputation.">
    <div className="daa-page" aria-busy={!!work.busy}>
      <ScenarioPicker scenarios={work.scenarios} selectedId={work.selectedId} onSelect={(id) => { setSelection({ backbone: null, ids: [] }); work.select(id); }} onLoad={() => { setSelection({ backbone: null, ids: [] }); void work.load(); }} busy={work.busy} catalogBusy={work.catalogBusy} catalogError={work.catalogError} error={work.error} onRetry={work.retryCatalog} />
      <div className="daa-controls"><button type="button" disabled={!ready} onClick={() => { setSelection({ backbone: null, ids: [] }); void work.run("Building backbone", daaApi.backbone, (value) => ({ original: value, repair: null })); }}>Run backbone</button></div>
      {!work.scenario ? <EmptyState>Load a scenario to inspect its explicit topology and run reconnection.</EmptyState> : <Panel title={work.scenario.name}>
        <ScenarioMap scenario={work.scenario} chosenLinkIds={(repair?.incremental ?? original?.backbone)?.chosenLinks.map((edge) => edge.id)} compromisedLinkIds={repair?.compromisedLinkIds} isolatedHostIds={(repair?.incremental ?? original?.backbone)?.unreachable.map((host) => host.id)} />
      </Panel>}
      {original ? <>
        <BackboneOutput title="Original backbone" value={original.backbone} />
        <Panel title="Repair selection" subtitle="Choose exactly 3 distinct links from the current original backbone, or let the server choose its highest-cost 3.">
          <EdgeTable title="Chosen backbone links" edges={chosen.map((edge) => repair?.compromisedLinkIds.includes(edge.id) ? { ...edge, compromised: true } : edge)} scenario={original.scenario} selected={selected} onToggle={toggle} disabled={!!work.busy} />
          <p role="status">{selected.length} / 3 chosen links selected. {valid ? "Ready to repair." : "Select exactly 3 distinct current chosen links."}</p>
          {chosen.length < 3 ? <EmptyState>This backbone has fewer than 3 chosen links; repair is unavailable.</EmptyState> : null}
          <div className="daa-controls">
            <button type="button" disabled={!ready || !valid} onClick={() => runRepair(selected)}>Repair selected 3 links</button>
            <button type="button" disabled={!ready || chosen.length < 3} onClick={() => runRepair()}>Repair highest-cost 3 (server default)</button>
          </div>
        </Panel>
        <Panel title="Kruskal candidates"><EdgeTable title="Sorted candidate links" edges={original.backbone.sortedCandidates} scenario={original.scenario} /></Panel>
      </> : work.scenario ? <EmptyState>No backbone result yet. Run backbone to enable repair.</EmptyState> : null}
      {repair ? <>
        <Panel title="Repair verification"><p>Compromised link IDs: {repair.compromisedLinkIds.join(", ") || "None"}</p><p>Same edges: {repair.sameEdges ? "Yes" : "No"} · Same cost: {repair.sameCost ? "Yes" : "No"}</p><p>The original loaded scenario is retained; the repair overlay marks compromised links without mutating it.</p></Panel>
        <div className="daa-grid"><BackboneOutput title="Incremental repair" value={repair.incremental} /><BackboneOutput title="Full recomputation" value={repair.fullRecompute} /></div>
        <div className="daa-grid"><Panel title="Incremental chosen links"><EdgeTable title="Repaired backbone links" edges={repair.incremental.chosenLinks} scenario={repair.scenario} /></Panel><Panel title="Full recomputation chosen links"><EdgeTable title="Recomputed backbone links" edges={repair.fullRecompute.chosenLinks} scenario={repair.scenario} /></Panel></div>
      </> : null}
    </div>
  </Page>;
}
