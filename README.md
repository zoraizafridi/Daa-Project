# Cyber Incident Response Coordination System

CT 363 DAA project hosted by a minimal Java 17 / React Network Threat Detection application. The landing page, sign-in portal, live Overview/map and basic simulated detection remain. Independent Admin scenario pages demonstrate deterministic Kruskal reconnection, exact multiple-knapsack allocation and copy-only sensitivity analysis.

This is a local educational application, not a production incident-response or network-attack platform. All injected attacks are simulated events. Alerts and audit diagnostics are bounded, memory-only data.

## Build and run

Requirements: JDK 17+, Maven, and Node.js compatible with the installed Vite version (the workspace uses Node 24). Run from the project root:

```bash
npm ci --prefix web-client
npm run build --prefix web-client
mvn test
npm run typecheck --prefix web-client
npm test --prefix web-client
mvn clean package
java -jar target/threat-detection-1.0-SNAPSHOT.jar --web --port 8080 --seed 42
```

Open `http://localhost:8080/`. Build the frontend before packaging: Vite writes assets to `src/main/resources/web`, and a clean Maven package prevents stale hashed assets from remaining in the jar.

| Demo account | Password | Access |
| --- | --- | --- |
| admin | admin | Live controls and all three DAA pages |
| analyst | analyst | Live Overview and simulated controls; no DAA API/page access |
| viewer | viewer | Read-only live Overview |

These credentials are for the shipped local demo only. Production mode (`-Dntd.app.demoMode=false`) rejects shipped credentials; supply `NTD_ADMIN_PASSWORD`, `NTD_ANALYST_PASSWORD` and `NTD_VIEWER_PASSWORD`. See [security guidance](docs/security.md).

The CLI supports `--web`, `--port`, `--seed` and `--config`. `--config` overlays custom properties onto the packaged configuration; `-Dntd.<key>=<value>` overrides an individual key. There are no Zeek import/replay, database, export or deployment commands.

## Pages

- **Landing / Login:** retained entry and authenticated session flow.
- **Overview** (`/portal`): existing live map, risk on the 0–100 scale, basic detector alerts and permitted simulator controls. `/portal/overview` redirects here.
- **Reconnection** (`/portal/reconnection`): scenario topology from explicit links, sorted candidates, chosen forest, outside-SOC host names, and exactly-three-tree-link repair versus full recomputation.
- **Mitigation** (`/portal/mitigation`): once-grown task values, team allocations, overweight and unassigned reasons, and DP/greedy/brute-force comparison.
- **Pipeline** (`/portal/pipeline`): backbone-first allocation, top-two-host sensitivity and the edge-case runner.

DAA pages require Admin both in navigation/routing and on the server. Their scenario state is separate from the traffic simulator and live network. Scenario maps use explicit SVG edges rather than the live map's inferred visual topology.

## Scenario data

Bundled JSON resources are in `src/main/resources/scenarios`:

| ID | Purpose |
| --- | --- |
| main-12-hosts-22-links | Worked example: 12 hosts, 22 links, teams of 6/4/3 hours |
| disconnected | Two components plus an isolated host; outside-SOC work is dropped |
| over-demand | Individually fitting tasks exceed aggregate capacity; best partial allocation |
| oversized-task | One task exceeds every team; fitting work is still allocated |
| large-200-hosts-1000-links | Generated deterministically with seed 42, including a connecting chain and unique undirected endpoint pairs |

`ScenarioLoader` only loads allowlisted IDs. Custom scenario objects are accepted by DAA request selectors and go through the same strict loader. A scenario contains:

- `id`, `name`, `socHostId`;
- `hosts`: `id`, unique `name`, integer `riskScore` and `criticality` in 1–10;
- `links`: `id`, `sourceId`, `targetId`, positive finite `cost`, `compromised`;
- `teams`: unique `id` and distinct positive integer `capacity`, exactly three or four teams;
- `tasks`: unique `id`, existing `hostName`, positive integer `effortHours`, nonnegative integer `baseValue` and `daysUnpatched`.

The network must contain a host and the declared SOC must exist. IDs must be nonblank and unique within their collections; link endpoints must exist and differ. Unknown/missing/null fields, duplicate JSON keys, trailing JSON, scalar coercion and fractional integers are rejected with clear errors. There is no global conversion of live asset risk to the scenario scale. Records copy their lists, and network/task/team conversions create fresh objects.

## Algorithms and OOAD structure

### Reconnection

The existing `BackbonePlanner` and `UnionFind` are extended, not replaced. Kruskal orders eligible links by:

1. cost ascending;
2. endpoint risk-score sum descending;
3. link ID ascending.

Compromised links are excluded. UnionFind uses union by rank and path compression. Every eligible edge is scanned so disconnected inputs return a complete minimum spanning forest. Reachability is relative to the declared SOC, not merely membership in any component. Empty networks and missing SOC declarations are errors.

Full planning retains its sorted candidates and chosen forest. Repair marks exactly three originally eligible chosen-tree links as newly compromised on a scenario copy. Defaults are the highest-cost three, with ID tie-breaking; callers may select three distinct tree IDs. Repair unions surviving tree links, then scans only cached non-tree candidates in their original order. It does not sort again. The final chosen subsequences are merged by cached position so their floating-point cost summation order matches full recomputation on the same reduced graph.

For `v` hosts and `m` links, full Kruskal is **O(m log m)** generally, or **O(m log v)** for a simple graph. Union/find work is amortized inverse-Ackermann. Repair reuses already-paid preprocessing; its algorithmic work is survivor unions, non-tree candidate scans, SOC reachability and ordered output merging. Preparing the reduced input still takes linear work and is excluded from the reported repair-core timer.

### Mitigation

`AssignmentPlanner` retains its exact recurrence, mixed-radix state indexing, full choice table and backtracking. A task is skipped or assigned once to one team with sufficient remaining capacity. Team skills do not participate. Tasks are ordered by ID; equal-value choices prefer skipping, then the lower team index when comparing assignments.

For `n` eligible tasks and team capacities `C1 ... Ck`:

- **Time:** O(n × k × product(Cj + 1)).
- **Space:** O(n × product(Cj + 1)).
- **Actual allocated table cells:** `(n + 1) × product(Cj + 1)`.

The base layer is included in the configured `dp.max.states` bound. This is exact **pseudo-polynomial** DP, not polynomial in the binary encoding of capacities. Overweight tasks are removed before allocating the table, so `n` is the eligible count. DAA empty work retains the base layer; the legacy generic empty path allocates no table. Table-cell counts and team-feasibility transition operations are separate metrics.

`AssignmentSolver` is the strategy contract, and `AssignmentSolverFactory` selects DP, greedy or brute force:

- **BruteForceAssignment:** independent exhaustive assignment/skip search; hard limit of 12 submitted tasks, checked before excluding overweight work. Worst-case O((k + 1)^n).
- **GreedyRatioAssignment:** value/hour descending with exact cross-product comparison and ID ties; first fitting team in input order. O(n log n + nk). It is a baseline, not an optimality guarantee.

Outputs partition tasks into assigned, excluded-overweight and eligible-unassigned groups, with explicit reasons. Zero-value tasks are labelled separately rather than falsely described as capacity-blocked. Tests compare DP value with brute-force value and require DP value to be at least greedy value.

### Linear age growth

The historical configuration key `decay.rate` remains, but it is **linear age growth**, not exponential decay. The shipped rate is `0.1`:

```text
adjustedValue = round(baseValue × (1 + rate × daysUnpatched))
```

Each eligible task is adjusted once before any solver runs; comparisons receive identical grown tasks. Negative/nonfinite rates and grown-value overflow are rejected. Input tasks remain unchanged.

### Coordination and sensitivity

`ResponseCoordinator` acts as the DAA facade:

```text
fresh scenario network → backbone → missing/outside-SOC task reasons
                      → once-rounded age growth → exact DP
```

`SensitivityService` selects the top two non-SOC hosts by risk descending, criticality descending and ID ascending. It removes them and incident links only from an independent copy, runs before/after coordination, and reports chosen-edge/cost/isolation/task-drop/new/moved/value differences. It never calls the live facade's mutating response overload and never overwrites `lastResponsePlan`.

For the main fixture at rate `0.1`, the worked example is **backbone cost 66 → 45** and **assigned value 290 → 200** after removing Host 10 and Host 11. Task values shown in the pages come from server results, not hardcoded example outputs.

The live application retains its Detector strategy/factory, alert observers and `IncidentResponseFacade`; the new algorithms are scenario-only services behind the same authenticated portal.

## Counters and timing

`OperationCounter` reports:

- `comparisons`: candidate-edge sort comparator invocations;
- `findCalls`: all find invocations, including recursive path compression and SOC checks;
- `unions`: all union attempts, including retained-tree initialization during repair;
- `edgesScanned`: candidate edges visited; repair does not count survivor-tree initialization as candidate scanning.

Assignment `stateCount` means allocated DP table cells; baseline solvers allocate no DP table. `transitionOps` counts team-feasibility checks, not table cells.

`AlgorithmTimer` uses `System.nanoTime()`, **one warmup followed by five measured executions**, and returns their median. Scenario loading/copying, age growth, repair-ID selection and repair preparation are outside the clock. Full planning times candidate sorting plus algorithm execution; repair reuses its retained ordering. Solver input ordering and table allocation are part of solver execution.

These are local server algorithm timings, **not HTTP latency** and not guarantees that repair or any solver will always be faster. JVM compilation, GC and scheduling affect small samples. Tests assert correctness, counter semantics and measurement metadata, never a wall-clock speed threshold. The regression suite includes 200 seeded repair/full equivalence graphs and independent assignment-oracle cases.

## DAA API

Every DAA endpoint requires an authenticated Admin session. POSTs require the existing CSRF cookie/header pair (`csrf` / `X-CSRF-Token`). Login rotation, logout invalidation, throttling, cookie policy and session lifetimes are retained.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | /api/daa/scenarios | Catalog |
| POST | /api/daa/scenarios/load | Validated scenario |
| POST | /api/daa/backbone | Full sorted forest and counters/timing |
| POST | /api/daa/backbone/repair | Three-link incremental/full comparison |
| POST | /api/daa/assign | Once-grown exact assignment |
| POST | /api/daa/compare | DP/ratio-greedy/brute-force comparison |
| POST | /api/daa/pipeline | Coordinated report and drop reasons |
| POST | /api/daa/sensitivity | Independent top-two-host analysis |
| POST | /api/daa/edge-cases | Bundled edge-case reports |

Algorithm requests select a scenario using either `{ "scenarioId": "main-12-hosts-22-links" }` or `{ "scenario": <complete scenario object> }`, not both. Catalog loading accepts `scenarioId` only. Repair may additionally contain `linkIds`; omission selects the default highest-cost three. The edge-case runner accepts `{}`. Brute-force unavailability above 12 tasks is reported without suppressing DP and greedy results. Unreachable hosts are always `{id, name}` objects in DAA responses.

## Scope and security

Retained: landing/login, live Overview/map, four basic detectors, event queue/cooldown, bounded alert repository, simulator, authentication/session/CSRF and sanitized in-memory audit diagnostics.

Removed: persistent database/storage administration, incident/containment workflows, threat-intelligence and ML extras, Zeek/import/replay, flows, exports/reports, notifications, compliance, benchmark CLI, deployment files and their obsolete UI/API surfaces. Optional sensor placement, Dijkstra, Tarjan and Prim are not implemented.

The local demo does not provide durable auditing, HTTPS termination or production hardening by itself. Use external TLS and explicit production credentials before exposing it beyond the intended local environment. Secure cookies/HSTS require HTTPS; follow [security guidance](docs/security.md).
