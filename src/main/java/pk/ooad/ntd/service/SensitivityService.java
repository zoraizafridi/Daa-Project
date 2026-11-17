package pk.ooad.ntd.service;

import pk.ooad.ntd.domain.Link;
import pk.ooad.ntd.domain.NetworkAsset;
import pk.ooad.ntd.scenario.Scenario;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

public final class SensitivityService {
    public record MovedTask(String taskId, String fromTeamId, String toTeamId) {
    }

    public record Report(List<Scenario.Host> removedHosts, ResponseCoordinator.Report before,
                         ResponseCoordinator.Report after, List<String> removedLinkIds,
                         List<String> addedLinkIds, double costDelta, int valueDelta,
                         List<Scenario.Host> newlyIsolatedHosts,
                         List<ResponseCoordinator.DroppedTask> droppedTasks,
                         List<String> newlyAssignedTaskIds, List<String> noLongerAssignedTaskIds,
                         List<MovedTask> movedTasks) {
        public Report {
            removedHosts = List.copyOf(removedHosts);
            removedLinkIds = List.copyOf(removedLinkIds);
            addedLinkIds = List.copyOf(addedLinkIds);
            newlyIsolatedHosts = List.copyOf(newlyIsolatedHosts);
            droppedTasks = List.copyOf(droppedTasks);
            newlyAssignedTaskIds = List.copyOf(newlyAssignedTaskIds);
            noLongerAssignedTaskIds = List.copyOf(noLongerAssignedTaskIds);
            movedTasks = List.copyOf(movedTasks);
        }
    }

    public Report analyze(Scenario scenario) {
        if (scenario == null) throw new IllegalArgumentException("scenario must not be null");
        List<Scenario.Host> removedHosts = scenario.hosts().stream()
                .filter(host -> !host.id().equals(scenario.socHostId()))
                .sorted(Comparator.comparingInt(Scenario.Host::riskScore).reversed()
                        .thenComparing(Comparator.comparingInt(Scenario.Host::criticality).reversed())
                        .thenComparing(Scenario.Host::id))
                .limit(2).sorted(Comparator.comparing(Scenario.Host::id)).toList();
        Set<String> removedIds = removedHosts.stream().map(Scenario.Host::id).collect(Collectors.toSet());
        ResponseCoordinator coordinator = new ResponseCoordinator();
        ResponseCoordinator.Report before = coordinator.run(scenario);
        ResponseCoordinator.Report after = coordinator.run(scenario, removedIds);

        Set<String> beforeLinks = chosenLinkIds(before);
        Set<String> afterLinks = chosenLinkIds(after);
        List<String> removedLinks = difference(beforeLinks, afterLinks);
        List<String> addedLinks = difference(afterLinks, beforeLinks);
        Set<String> beforeUnreachable = unreachableIds(before);
        Set<String> afterUnreachable = unreachableIds(after);
        List<Scenario.Host> newlyIsolated = after.activeHosts().stream()
                .filter(host -> !removedIds.contains(host.id()))
                .filter(host -> afterUnreachable.contains(host.id()) && !beforeUnreachable.contains(host.id()))
                .sorted(Comparator.comparing(Scenario.Host::id)).toList();

        Map<String, String> beforeAssignments = assignedTeams(before);
        Map<String, String> afterAssignments = assignedTeams(after);
        List<String> newlyAssigned = difference(afterAssignments.keySet(), beforeAssignments.keySet());
        List<String> noLongerAssigned = difference(beforeAssignments.keySet(), afterAssignments.keySet());
        List<MovedTask> moved = beforeAssignments.entrySet().stream()
                .filter(entry -> afterAssignments.containsKey(entry.getKey()))
                .filter(entry -> !entry.getValue().equals(afterAssignments.get(entry.getKey())))
                .map(entry -> new MovedTask(entry.getKey(), entry.getValue(), afterAssignments.get(entry.getKey())))
                .sorted(Comparator.comparing(MovedTask::taskId)).toList();
        return new Report(removedHosts, before, after, removedLinks, addedLinks,
                after.backbone().result().getTotalWeight() - before.backbone().result().getTotalWeight(),
                after.assignment().result().getTotalValue() - before.assignment().result().getTotalValue(),
                newlyIsolated, after.droppedTasks(), newlyAssigned, noLongerAssigned, moved);
    }

    private static Set<String> chosenLinkIds(ResponseCoordinator.Report report) {
        return report.backbone().result().getChosenLinks().stream().map(Link::getId).collect(Collectors.toSet());
    }

    private static Set<String> unreachableIds(ResponseCoordinator.Report report) {
        return report.backbone().result().getUnreachable().stream()
                .map(NetworkAsset::getId).collect(Collectors.toSet());
    }

    private static Map<String, String> assignedTeams(ResponseCoordinator.Report report) {
        Map<String, String> assignments = new TreeMap<>();
        report.assignment().result().getAssignments().forEach((team, tasks) ->
                tasks.forEach(task -> assignments.put(task.getId(), team.getId())));
        return assignments;
    }

    private static List<String> difference(Set<String> left, Set<String> right) {
        return left.stream().filter(id -> !right.contains(id)).sorted().toList();
    }
}
