package pk.ooad.ntd.web;

import pk.ooad.ntd.domain.AssignmentResult;
import pk.ooad.ntd.domain.BackboneResult;
import pk.ooad.ntd.domain.Link;
import pk.ooad.ntd.domain.LinkStatus;
import pk.ooad.ntd.domain.MitigationTask;
import pk.ooad.ntd.response.AlgorithmTimer.Measurement;
import pk.ooad.ntd.scenario.Scenario;
import pk.ooad.ntd.service.ResponseCoordinator;
import pk.ooad.ntd.service.SensitivityService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit, cycle-free wire contracts; domain objects never become JSON map keys. */
public final class DaaDtos {
    private DaaDtos() {
    }

    public record ScenarioSummary(String id, String name, int hostCount, int linkCount,
                                  int teamCount, int taskCount) {
    }

    public record HostRef(String id, String name) {
    }

    public record Operations(long comparisons, long findCalls, long unions, long edgesScanned) {
    }

    public record BackboneDto(List<Scenario.Edge> chosenLinks, List<Scenario.Edge> sortedCandidates,
                              double totalWeight, boolean connected, List<HostRef> unreachable,
                              Operations operations, long medianNanos, int warmupRuns, int measuredRuns) {
    }

    public record AdjustedTaskDto(String id, String hostId, String hostName, int effortHours,
                                  int baseValue, int daysUnpatched, int adjustedValue) {
    }

    public record TeamDto(String id, int capacity, int usedHours, List<AdjustedTaskDto> tasks) {
    }

    public record TaskDispositionDto(AdjustedTaskDto task, String reason) {
    }

    public record AssignmentDto(String solver, int totalValue, long stateCount, long transitionOps,
                                long medianNanos, int warmupRuns, int measuredRuns,
                                List<TeamDto> teams, List<TaskDispositionDto> excluded,
                                List<TaskDispositionDto> unassigned) {
    }

    public record BackboneResponse(Scenario scenario, BackboneDto backbone) {
    }

    public record RepairResponse(Scenario scenario, List<String> compromisedLinkIds,
                                 BackboneDto original, BackboneDto incremental, BackboneDto fullRecompute,
                                 boolean sameEdges, boolean sameCost) {
    }

    public record AssignmentResponse(Scenario scenario, double ageGrowthRate, List<AdjustedTaskDto> tasks,
                                     AssignmentDto assignment) {
    }

    public record ComparisonResponse(Scenario scenario, double ageGrowthRate, List<AdjustedTaskDto> tasks,
                                     AssignmentDto dp, AssignmentDto greedy, AssignmentDto bruteForce,
                                     String bruteForceError) {
    }

    public record DroppedTaskDto(Scenario.Task task, String reason) {
    }

    public record PipelineDto(Scenario scenario, List<Scenario.Host> activeHosts, List<Scenario.Edge> activeLinks,
                              double ageGrowthRate, List<AdjustedTaskDto> tasks, BackboneDto backbone,
                              AssignmentDto assignment, List<DroppedTaskDto> droppedTasks) {
    }

    public record MovedTaskDto(String taskId, String fromTeamId, String toTeamId) {
    }

    public record SensitivityDto(List<Scenario.Host> removedHosts, PipelineDto before, PipelineDto after,
                                 List<String> removedLinkIds, List<String> addedLinkIds, double costDelta,
                                 int valueDelta, List<HostRef> newlyIsolatedHosts,
                                 List<DroppedTaskDto> droppedTasks, List<String> newlyAssignedTaskIds,
                                 List<String> noLongerAssignedTaskIds, List<MovedTaskDto> movedTasks) {
    }

    public record EdgeCaseDto(String id, String name, PipelineDto report, boolean passed, String expectation) {
    }

    public record EdgeCasesResponse(List<EdgeCaseDto> results, boolean allPassed) {
    }

    public static ScenarioSummary summary(Scenario scenario) {
        return new ScenarioSummary(scenario.id(), scenario.name(), scenario.hosts().size(),
                scenario.links().size(), scenario.teams().size(), scenario.tasks().size());
    }

    public static BackboneDto backbone(Measurement<BackboneResult> measurement) {
        BackboneResult result = measurement.result();
        var operations = result.getOperations();
        return new BackboneDto(result.getChosenLinks().stream().map(DaaDtos::edge).toList(),
                result.getSortedCandidates().stream().map(DaaDtos::edge).toList(),
                result.getTotalWeight(), result.isConnected(), result.getUnreachable().stream()
                .map(host -> new HostRef(host.getId(), host.getName())).toList(),
                new Operations(operations.comparisons(), operations.findCalls(), operations.unions(),
                        operations.edgesScanned()), measurement.medianNanos(),
                measurement.warmupRuns(), measurement.measuredRuns());
    }

    private static Scenario.Edge edge(Link link) {
        return new Scenario.Edge(link.getId(), link.getSource().getId(), link.getTarget().getId(),
                link.getBaseCost(), link.getStatus() == LinkStatus.COMPROMISED);
    }

    public static List<AdjustedTaskDto> tasks(Scenario scenario, List<MitigationTask> tasks) {
        Map<String, Scenario.Task> inputs = taskInputs(scenario);
        return tasks.stream().map(task -> task(task, inputs)).toList();
    }

    public static AssignmentDto assignment(Scenario scenario, String solver,
                                            Measurement<AssignmentResult> measurement) {
        AssignmentResult result = measurement.result();
        Map<String, Scenario.Task> inputs = taskInputs(scenario);
        Map<String, List<MitigationTask>> assignments = new LinkedHashMap<>();
        result.getAssignments().forEach((team, assigned) -> assignments.put(team.getId(), assigned));
        List<TeamDto> teams = new ArrayList<>();
        // Iterate the scenario, not the result map, so even unused teams have a row.
        for (Scenario.Team team : scenario.teams()) {
            List<MitigationTask> assigned = assignments.getOrDefault(team.id(), List.of());
            teams.add(new TeamDto(team.id(), team.capacity(),
                    assigned.stream().mapToInt(MitigationTask::getCost).sum(),
                    assigned.stream().map(task -> task(task, inputs)).toList()));
        }
        List<TaskDispositionDto> excluded = result.getExcludedReasons().stream()
                .map(row -> new TaskDispositionDto(task(row.task(), inputs), row.reason())).toList();
        List<TaskDispositionDto> unassigned = result.getUnassigned().stream()
                .map(row -> new TaskDispositionDto(task(row.task(), inputs), row.reason())).toList();
        return new AssignmentDto(solver, result.getTotalValue(), result.getStateCount(),
                result.getTransitionOps(), measurement.medianNanos(), measurement.warmupRuns(),
                measurement.measuredRuns(), List.copyOf(teams), excluded, unassigned);
    }

    private static Map<String, Scenario.Task> taskInputs(Scenario scenario) {
        Map<String, Scenario.Task> inputs = new LinkedHashMap<>();
        scenario.tasks().forEach(task -> inputs.put(task.id(), task));
        return inputs;
    }

    private static AdjustedTaskDto task(MitigationTask task, Map<String, Scenario.Task> inputs) {
        Scenario.Task input = inputs.get(task.getId());
        if (input == null) {
            throw new IllegalStateException("Missing original task input: " + task.getId());
        }
        return new AdjustedTaskDto(task.getId(), task.getTarget().getId(), task.getTarget().getName(),
                task.getCost(), input.baseValue(), input.daysUnpatched(), task.getValue());
    }

    public static PipelineDto pipeline(ResponseCoordinator.Report report) {
        return new PipelineDto(report.scenario(), report.activeHosts(), report.activeLinks(),
                report.ageGrowthRate(), tasks(report.scenario(), report.tasks()), backbone(report.backbone()),
                assignment(report.scenario(), "DP", report.assignment()), dropped(report.droppedTasks()));
    }

    private static List<DroppedTaskDto> dropped(List<ResponseCoordinator.DroppedTask> dropped) {
        return dropped.stream().map(row -> new DroppedTaskDto(row.task(), row.reason())).toList();
    }

    public static SensitivityDto sensitivity(SensitivityService.Report report) {
        return new SensitivityDto(report.removedHosts(), pipeline(report.before()), pipeline(report.after()),
                report.removedLinkIds(), report.addedLinkIds(), report.costDelta(), report.valueDelta(),
                report.newlyIsolatedHosts().stream().map(host -> new HostRef(host.id(), host.name())).toList(),
                dropped(report.droppedTasks()), report.newlyAssignedTaskIds(), report.noLongerAssignedTaskIds(),
                report.movedTasks().stream().map(row -> new MovedTaskDto(row.taskId(), row.fromTeamId(),
                        row.toTeamId())).toList());
    }
}
