package pk.ooad.ntd.response;

import pk.ooad.ntd.domain.AnalystTeam;
import pk.ooad.ntd.domain.AssignmentResult;
import pk.ooad.ntd.domain.MitigationTask;
import pk.ooad.ntd.domain.TaskDisposition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class AssignmentValidation {
    private AssignmentValidation() {
    }

    record Input(List<AnalystTeam> teams, List<MitigationTask> eligible, List<MitigationTask> excluded) {
    }

    static Input validate(List<MitigationTask> tasks, List<AnalystTeam> teams, boolean daa) {
        Objects.requireNonNull(tasks, "tasks must not be null");
        Objects.requireNonNull(teams, "teams must not be null");
        List<AnalystTeam> teamList = List.copyOf(teams);
        List<MitigationTask> orderedTasks = new ArrayList<>(List.copyOf(tasks));
        if (daa ? teamList.size() < 3 || teamList.size() > 4 : teamList.size() > 4) {
            throw new IllegalArgumentException(daa ? "DAA assignment requires 3 or 4 teams"
                    : "Multiple-knapsack planning supports at most 4 teams");
        }
        Set<String> teamIds = new HashSet<>();
        Set<Integer> capacities = new HashSet<>();
        for (AnalystTeam team : teamList) {
            checkId(team.getId(), teamIds, "Team", daa);
            if (daa ? team.getCapacity() <= 0 : team.getCapacity() < 0) {
                throw new IllegalArgumentException(daa ? "Team capacities must be positive"
                        : "Team capacities must not be negative");
            }
            if (daa && !capacities.add(team.getCapacity())) {
                throw new IllegalArgumentException("DAA team capacities must be distinct");
            }
        }
        Set<String> taskIds = new HashSet<>();
        for (MitigationTask task : orderedTasks) {
            checkId(task.getId(), taskIds, "Task", daa);
            if (daa ? task.getCost() <= 0 : task.getCost() < 0) {
                throw new IllegalArgumentException(daa ? "Mitigation task effort must be positive"
                        : "Mitigation task costs must not be negative");
            }
            if (daa && (task.getValue() < 0 || task.getDaysUnpatched() < 0)) {
                throw new IllegalArgumentException("DAA task values and days unpatched must not be negative");
            }
        }
        orderedTasks.sort(Comparator.comparing(MitigationTask::getId));
        List<MitigationTask> excluded = new ArrayList<>();
        List<MitigationTask> eligible = new ArrayList<>();
        int maxCapacity = teamList.stream().mapToInt(AnalystTeam::getCapacity).max().orElse(-1);
        for (MitigationTask task : orderedTasks) {
            if (teamList.isEmpty() || task.getCost() > maxCapacity) excluded.add(task);
            else eligible.add(task);
        }
        return new Input(teamList, List.copyOf(eligible), List.copyOf(excluded));
    }

    private static void checkId(String id, Set<String> seen, String kind, boolean daa) {
        if (daa && (id == null || id.isBlank())) {
            throw new IllegalArgumentException(kind + " IDs must not be blank");
        }
        if (!seen.add(id)) {
            throw new IllegalArgumentException("Duplicate " + kind.toLowerCase(java.util.Locale.ROOT) + " ID: " + id);
        }
    }

    static AssignmentResult result(Input input, Map<AnalystTeam, List<MitigationTask>> assignments,
                                   int totalValue, long stateCount, long transitionOps) {
        Set<String> assignedIds = new HashSet<>();
        assignments.values().forEach(list -> list.forEach(task -> assignedIds.add(task.getId())));
        List<TaskDisposition> unassigned = input.eligible().stream()
                .filter(task -> !assignedIds.contains(task.getId()))
                .map(task -> new TaskDisposition(task, task.getValue() <= 0
                        ? "NON_POSITIVE_VALUE" : "UNASSIGNED_CAPACITY"))
                .toList();
        return new AssignmentResult(assignments, totalValue, input.excluded(), stateCount, transitionOps, unassigned);
    }
}
