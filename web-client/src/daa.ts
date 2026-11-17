import { readJson } from "./api";

export interface ScenarioHost { id: string; name: string; riskScore: number; criticality: number }
export interface ScenarioEdge { id: string; sourceId: string; targetId: string; cost: number; compromised: boolean }
export interface ScenarioTask { id: string; hostName: string; effortHours: number; baseValue: number; daysUnpatched: number }
export interface Scenario {
  id: string;
  name: string;
  socHostId: string;
  hosts: ScenarioHost[];
  links: ScenarioEdge[];
  teams: { id: string; capacity: number }[];
  tasks: ScenarioTask[];
}
export interface ScenarioSummary { id: string; name: string; hostCount: number; linkCount: number; teamCount: number; taskCount: number }
export interface Backbone {
  chosenLinks: ScenarioEdge[];
  sortedCandidates: ScenarioEdge[];
  totalWeight: number;
  connected: boolean;
  unreachable: { id: string; name: string }[];
  operations: { comparisons: number; findCalls: number; unions: number; edgesScanned: number };
  medianNanos: number;
  warmupRuns: number;
  measuredRuns: number;
}
export interface AdjustedTask extends ScenarioTask { hostId: string; adjustedValue: number }
export interface TaskReason { task: AdjustedTask; reason: string }
export interface Assignment {
  solver: "DP" | "GREEDY" | "BRUTE_FORCE";
  totalValue: number;
  stateCount: number;
  transitionOps: number;
  medianNanos: number;
  warmupRuns: number;
  measuredRuns: number;
  teams: { id: string; capacity: number; usedHours: number; tasks: AdjustedTask[] }[];
  excluded: TaskReason[];
  unassigned: TaskReason[];
}
export interface BackboneResponse { scenario: Scenario; backbone: Backbone }
export interface RepairResponse {
  scenario: Scenario;
  compromisedLinkIds: string[];
  original: Backbone;
  incremental: Backbone;
  fullRecompute: Backbone;
  sameEdges: boolean;
  sameCost: boolean;
}
export interface AssignResponse { scenario: Scenario; ageGrowthRate: number; tasks: AdjustedTask[]; assignment: Assignment }
export interface CompareResponse {
  scenario: Scenario;
  ageGrowthRate: number;
  tasks: AdjustedTask[];
  dp: Assignment;
  greedy: Assignment;
  bruteForce: Assignment | null;
  bruteForceError: string | null;
}
export interface DroppedTask { task: ScenarioTask; reason: string }
export interface PipelineReport {
  scenario: Scenario;
  activeHosts: ScenarioHost[];
  activeLinks: ScenarioEdge[];
  ageGrowthRate: number;
  tasks: AdjustedTask[];
  backbone: Backbone;
  assignment: Assignment;
  droppedTasks: DroppedTask[];
}
export interface SensitivityResponse {
  removedHosts: ScenarioHost[];
  before: PipelineReport;
  after: PipelineReport;
  removedLinkIds: string[];
  addedLinkIds: string[];
  costDelta: number;
  valueDelta: number;
  newlyIsolatedHosts: { id: string; name: string }[];
  droppedTasks: DroppedTask[];
  newlyAssignedTaskIds: string[];
  noLongerAssignedTaskIds: string[];
  movedTasks: { taskId: string; fromTeamId: string; toTeamId: string }[];
}
export interface EdgeCasesResponse {
  results: { id: string; name: string; report: PipelineReport; passed: boolean; expectation: string }[];
  allPassed: boolean;
}

function post<T>(endpoint: string, body: object): Promise<T> {
  return readJson<T>(`/api/daa/${endpoint}`, { method: "POST", body: JSON.stringify(body) });
}

// All transport, credentials, CSRF and session-expiry handling stay in api.ts.
export const daaApi = {
  scenarios: () => readJson<ScenarioSummary[]>("/api/daa/scenarios"),
  load: (scenarioId: string) => post<Scenario>("scenarios/load", { scenarioId }),
  backbone: (scenarioId: string) => post<BackboneResponse>("backbone", { scenarioId }),
  repair: (scenarioId: string, linkIds?: string[]) => post<RepairResponse>("backbone/repair", linkIds === undefined ? { scenarioId } : { scenarioId, linkIds }),
  assign: (scenarioId: string) => post<AssignResponse>("assign", { scenarioId }),
  compare: (scenarioId: string) => post<CompareResponse>("compare", { scenarioId }),
  pipeline: (scenarioId: string) => post<PipelineReport>("pipeline", { scenarioId }),
  sensitivity: (scenarioId: string) => post<SensitivityResponse>("sensitivity", { scenarioId }),
  edgeCases: () => post<EdgeCasesResponse>("edge-cases", {}),
};

export function validRepairSelection(chosen: ScenarioEdge[], selected: string[]): boolean {
  return selected.length === 3 && new Set(selected).size === 3 && selected.every((id) => chosen.some((edge) => edge.id === id));
}
