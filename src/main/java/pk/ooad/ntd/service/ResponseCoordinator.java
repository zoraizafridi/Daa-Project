package pk.ooad.ntd.service;

import pk.ooad.ntd.domain.AnalystTeam;
import pk.ooad.ntd.domain.AssignmentResult;
import pk.ooad.ntd.domain.BackboneResult;
import pk.ooad.ntd.domain.MitigationTask;
import pk.ooad.ntd.domain.Network;
import pk.ooad.ntd.domain.NetworkAsset;
import pk.ooad.ntd.response.AgeGrowth;
import pk.ooad.ntd.response.AlgorithmTimer;
import pk.ooad.ntd.response.AssignmentPlanner;
import pk.ooad.ntd.response.BackbonePlanner;
import pk.ooad.ntd.scenario.Scenario;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class ResponseCoordinator {
    public record DroppedTask(Scenario.Task task, String reason) {
    }

    public record Report(Scenario scenario, List<Scenario.Host> activeHosts,
                         List<Scenario.Edge> activeLinks, double ageGrowthRate,
                         List<MitigationTask> tasks,
                         AlgorithmTimer.Measurement<BackboneResult> backbone,
                         AlgorithmTimer.Measurement<AssignmentResult> assignment,
                         List<DroppedTask> droppedTasks) {
        public Report {
            activeHosts = List.copyOf(activeHosts);
            activeLinks = List.copyOf(activeLinks);
            tasks = List.copyOf(tasks);
            droppedTasks = List.copyOf(droppedTasks);
        }
    }

    public Report run(Scenario scenario) {
        return run(scenario, Set.of());
    }

    public Report run(Scenario scenario, Set<String> removedHostIds) {
        if (scenario == null) throw new IllegalArgumentException("scenario must not be null");
        if (removedHostIds == null) throw new IllegalArgumentException("removedHostIds must not be null");
        Set<String> removed = new HashSet<>(removedHostIds);
        Set<String> hostIds = scenario.hosts().stream().map(Scenario.Host::id).collect(Collectors.toSet());
        for (String id : removed) {
            if (id == null || !hostIds.contains(id)) {
                throw new IllegalArgumentException("Removed host ID must identify an existing host: " + id);
            }
            if (id.equals(scenario.socHostId())) {
                throw new IllegalArgumentException("The SOC host cannot be removed: " + id);
            }
        }

        // Resolve base targets before removal, so removed hosts are explicitly reported rather than lost.
        Network network = scenario.toNetwork();
        List<MitigationTask> baseTasks = scenario.toTasks(network);
        for (String id : removed) network.removeAsset(id);
        List<Scenario.Host> activeHosts = scenario.hosts().stream()
                .filter(host -> !removed.contains(host.id()))
                .sorted(Comparator.comparing(Scenario.Host::id)).toList();
        List<Scenario.Edge> activeLinks = scenario.links().stream()
                .filter(edge -> !removed.contains(edge.sourceId()) && !removed.contains(edge.targetId()))
                .sorted(Comparator.comparing(Scenario.Edge::id)).toList();

        // Each invocation owns its planner, including any backbone baseline it caches internally.
        BackbonePlanner backbonePlanner = new BackbonePlanner();
        BackbonePlanner.PreparedInput input = backbonePlanner.prepare(network, scenario.socHostId());
        AlgorithmTimer.Measurement<BackboneResult> backbone =
                AlgorithmTimer.measure(() -> backbonePlanner.plan(input));

        Map<String, Scenario.Task> metadata = new HashMap<>();
        for (Scenario.Task task : scenario.tasks()) metadata.put(task.id(), task);
        Set<String> unreachable = backbone.result().getUnreachable().stream()
                .map(NetworkAsset::getId).collect(Collectors.toSet());
        List<DroppedTask> dropped = new ArrayList<>();
        List<MitigationTask> remaining = new ArrayList<>();
        for (MitigationTask task : baseTasks) {
            String targetId = task.getTarget().getId();
            if (network.getAsset(targetId) == null) {
                dropped.add(new DroppedTask(metadata.get(task.getId()), "MISSING_HOST"));
            } else if (unreachable.contains(targetId)) {
                dropped.add(new DroppedTask(metadata.get(task.getId()), "OUTSIDE_SOC_COMPONENT"));
            } else {
                remaining.add(task);
            }
        }
        dropped.sort(Comparator.comparing(drop -> drop.task().id()));
        remaining.sort(Comparator.comparing(MitigationTask::getId));
        double growthRate = AgeGrowth.configuredRate();
        List<MitigationTask> tasks = remaining.stream().map(task -> AgeGrowth.apply(task, growthRate)).toList();
        List<AnalystTeam> teams = scenario.toTeams();
        AssignmentPlanner assignmentPlanner = new AssignmentPlanner();
        AlgorithmTimer.Measurement<AssignmentResult> assignment =
                AlgorithmTimer.measure(() -> assignmentPlanner.planDaa(tasks, teams));
        return new Report(scenario, activeHosts, activeLinks, growthRate, tasks, backbone, assignment, dropped);
    }
}
