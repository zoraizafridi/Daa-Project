package pk.ooad.ntd.response;

import pk.ooad.ntd.domain.AnalystTeam;
import pk.ooad.ntd.domain.AssignmentResult;
import pk.ooad.ntd.domain.MitigationTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class BruteForceAssignment implements AssignmentSolver {
    @Override
    public AssignmentResult planDaa(List<MitigationTask> tasks, List<AnalystTeam> teams) {
        Objects.requireNonNull(tasks, "tasks must not be null");
        if (tasks.size() > 12) {
            throw new IllegalArgumentException("Brute-force assignment supports at most 12 submitted tasks");
        }
        AssignmentValidation.Input input = AssignmentValidation.validate(tasks, teams, true);
        Search search = new Search(input.eligible(), input.teams());
        search.visit(0, 0);
        Map<AnalystTeam, List<MitigationTask>> assignments = new LinkedHashMap<>();
        for (AnalystTeam team : input.teams()) assignments.put(team, new ArrayList<>());
        for (int i = 0; i < input.eligible().size(); i++) {
            int teamIndex = search.bestChoices[i];
            if (teamIndex >= 0) {
                assignments.get(input.teams().get(teamIndex)).add(input.eligible().get(i));
            }
        }
        assignments.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        return AssignmentValidation.result(input, assignments, search.bestValue, 0L, search.transitionOps);
    }

    private static final class Search {
        private final List<MitigationTask> tasks;
        private final int[] remaining;
        private final int[] choices;
        private int[] bestChoices;
        private int bestValue;
        private long transitionOps;

        private Search(List<MitigationTask> tasks, List<AnalystTeam> teams) {
            this.tasks = tasks;
            this.remaining = teams.stream().mapToInt(AnalystTeam::getCapacity).toArray();
            this.choices = new int[tasks.size()];
            Arrays.fill(choices, -1);
            this.bestChoices = choices.clone();
        }

        private void visit(int index, int totalValue) {
            if (index == tasks.size()) {
                if (totalValue > bestValue) {
                    bestValue = totalValue;
                    bestChoices = choices.clone();
                }
                return;
            }
            choices[index] = -1;
            visit(index + 1, totalValue);
            MitigationTask task = tasks.get(index);
            for (int teamIndex = 0; teamIndex < remaining.length; teamIndex++) {
                transitionOps++;
                if (task.getCost() > remaining[teamIndex]) continue;
                int candidateValue = Math.addExact(totalValue, task.getValue());
                remaining[teamIndex] -= task.getCost();
                choices[index] = teamIndex;
                visit(index + 1, candidateValue);
                remaining[teamIndex] += task.getCost();
            }
            choices[index] = -1;
        }
    }
}
