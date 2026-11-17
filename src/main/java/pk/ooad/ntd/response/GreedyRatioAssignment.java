package pk.ooad.ntd.response;

import pk.ooad.ntd.domain.AnalystTeam;
import pk.ooad.ntd.domain.AssignmentResult;
import pk.ooad.ntd.domain.MitigationTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GreedyRatioAssignment implements AssignmentSolver {
    @Override
    public AssignmentResult planDaa(List<MitigationTask> tasks, List<AnalystTeam> teams) {
        AssignmentValidation.Input input = AssignmentValidation.validate(tasks, teams, true);
        List<MitigationTask> ordered = new ArrayList<>(input.eligible());
        ordered.sort((left, right) -> {
            long leftProduct = (long) left.getValue() * right.getCost();
            long rightProduct = (long) right.getValue() * left.getCost();
            int ratioOrder = Long.compare(rightProduct, leftProduct);
            return ratioOrder != 0 ? ratioOrder : left.getId().compareTo(right.getId());
        });
        int[] remaining = input.teams().stream().mapToInt(AnalystTeam::getCapacity).toArray();
        Map<AnalystTeam, List<MitigationTask>> assignments = new LinkedHashMap<>();
        for (AnalystTeam team : input.teams()) assignments.put(team, new ArrayList<>());
        int totalValue = 0;
        long transitionOps = 0L;
        for (MitigationTask task : ordered) {
            if (task.getValue() <= 0) continue;
            for (int teamIndex = 0; teamIndex < remaining.length; teamIndex++) {
                transitionOps++;
                if (task.getCost() > remaining[teamIndex]) continue;
                totalValue = Math.addExact(totalValue, task.getValue());
                remaining[teamIndex] -= task.getCost();
                assignments.get(input.teams().get(teamIndex)).add(task);
                break;
            }
        }
        for (List<MitigationTask> assigned : assignments.values()) {
            assigned.sort(Comparator.comparing(MitigationTask::getId));
        }
        assignments.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        return AssignmentValidation.result(input, assignments, totalValue, 0L, transitionOps);
    }
}
