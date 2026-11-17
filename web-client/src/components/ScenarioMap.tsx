import { useId } from "react";
import type { Scenario } from "../daa";
import { EdgeTable } from "./DaaShared";
import { EmptyState } from "./ui";

export function ScenarioMap({ scenario, chosenLinkIds = [], compromisedLinkIds = [], isolatedHostIds = [], title = "Scenario topology" }: {
  scenario: Scenario; chosenLinkIds?: string[]; compromisedLinkIds?: string[]; isolatedHostIds?: string[]; title?: string;
}) {
  const labelId = useId();
  const descriptionId = useId();
  const hosts = [...scenario.hosts].sort((a, b) => a.id < b.id ? -1 : a.id > b.id ? 1 : 0);
  const size = Math.max(640, hosts.length * 12);
  const center = size / 2;
  const radius = size * 0.38;
  const positions = new Map(hosts.map((host, index) => {
    const angle = index * Math.PI * 2 / Math.max(hosts.length, 1) - Math.PI / 2;
    return [host.id, { x: center + radius * Math.cos(angle), y: center + radius * Math.sin(angle) }] as const;
  }));
  const incident = new Set(scenario.links.flatMap((edge) => [edge.sourceId, edge.targetId]));
  const isolated = new Set([...isolatedHostIds, ...hosts.filter((host) => !incident.has(host.id)).map((host) => host.id)]);
  const chosen = new Set(chosenLinkIds);
  const compromised = new Set(compromisedLinkIds);
  const names = new Map(hosts.map((host) => [host.id, host.name]));

  return <div className="daa-map">
    <svg viewBox={`0 0 ${size} ${size}`} role="img" aria-labelledby={`${labelId} ${descriptionId}`}>
      <title id={labelId}>{title}</title>
      <desc id={descriptionId}>Only explicit scenario links are drawn. Deterministic circular layout. Solid green links are chosen; dashed red links are compromised. Isolated host names and full host and edge data are listed below.</desc>
      {scenario.links.map((edge) => {
        const from = positions.get(edge.sourceId);
        const to = positions.get(edge.targetId);
        const broken = edge.compromised || compromised.has(edge.id);
        const className = `daa-map-edge${chosen.has(edge.id) ? " is-chosen" : ""}${broken ? " is-compromised" : ""}`;
        return <g key={edge.id} data-edge-id={edge.id} data-source-id={edge.sourceId} data-target-id={edge.targetId} className={className}>
          <title>{edge.id}: {names.get(edge.sourceId) ?? edge.sourceId} → {names.get(edge.targetId) ?? edge.targetId}; cost {edge.cost}; {broken ? "compromised" : "available"}{chosen.has(edge.id) ? "; chosen" : ""}</title>
          {from && to ? <><line x1={from.x} y1={from.y} x2={to.x} y2={to.y} />{scenario.links.length <= 40 ? <text x={(from.x + to.x) / 2} y={(from.y + to.y) / 2}>{edge.cost}</text> : null}</> : null}
        </g>;
      })}
      {hosts.map((host) => {
        const point = positions.get(host.id)!;
        return <g key={host.id} data-host-id={host.id} className={`daa-map-host${isolated.has(host.id) ? " is-isolated" : ""}${host.id === scenario.socHostId ? " is-soc" : ""}`}>
          <title>{host.name} ({host.id}); risk {host.riskScore}; criticality {host.criticality}{isolated.has(host.id) ? "; isolated" : ""}{host.id === scenario.socHostId ? "; SOC" : ""}</title>
          <circle cx={point.x} cy={point.y} r={hosts.length > 40 ? 7 : 12} />
          {hosts.length <= 40 || isolated.has(host.id) ? <text x={point.x} y={point.y + 26} textAnchor="middle">{host.name}</text> : null}
        </g>;
      })}
    </svg>
    {!hosts.length ? <EmptyState>No hosts in this scenario.</EmptyState> : null}
    <p className="daa-legend">Chosen: solid green · Compromised: dashed red · SOC: gold outline · Isolated: grey dashed outline</p>
    <p>Isolated hosts: {hosts.filter((host) => isolated.has(host.id)).map((host) => `${host.name} (${host.id})`).join(", ") || "None"}</p>
    {scenario.links.length > 40 ? <p>Edge cost text is hidden at this scale; every explicit edge retains its SVG title and table row.</p> : null}
    <div className="daa-table-wrap"><table><caption>Scenario hosts</caption><thead><tr><th scope="col">ID</th><th scope="col">Name</th><th scope="col">Risk</th><th scope="col">Criticality</th><th scope="col">State</th></tr></thead><tbody>
      {hosts.map((host) => <tr key={host.id}><td>{host.id}</td><td>{host.name}</td><td>{host.riskScore}</td><td>{host.criticality}</td><td>{isolated.has(host.id) ? "Isolated" : "Linked"}{host.id === scenario.socHostId ? " · SOC" : ""}</td></tr>)}
    </tbody></table></div>
    <EdgeTable title="Explicit scenario links" edges={scenario.links.map((edge) => compromised.has(edge.id) ? { ...edge, compromised: true } : edge)} scenario={scenario} />
  </div>;
}
