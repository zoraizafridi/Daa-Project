package pk.ooad.ntd.domain;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AssignmentResult {
    private final Map<AnalystTeam, List<MitigationTask>> assignments;
    private final int totalValue;
    private final List<MitigationTask> excluded;
    private final long stateCount;
    private final long transitionOps;
    private final List<TaskDisposition> unassigned;
    private final List<TaskDisposition> excludedReasons;

    public AssignmentResult(Map<AnalystTeam, List<MitigationTask>> assignments, int totalValue,
                            List<MitigationTask> excluded, long stateCount) {
        this(assignments, totalValue, excluded, stateCount, 0L, List.of());
    }

    public AssignmentResult(Map<AnalystTeam, List<MitigationTask>> assignments, int totalValue,
                            List<MitigationTask> excluded, long stateCount, long transitionOps,
                            List<TaskDisposition> unassigned) {
        Map<AnalystTeam, List<MitigationTask>> copy = assignments != null
                ? new LinkedHashMap<>(assignments) : new LinkedHashMap<>();
        copy.replaceAll((team, tasks) -> tasks != null ? List.copyOf(tasks) : List.of());
        this.assignments = Collections.unmodifiableMap(copy);
        this.totalValue = totalValue;
        this.excluded = Collections.unmodifiableList(excluded != null ? new ArrayList<>(excluded) : new ArrayList<>());
        this.stateCount = stateCount;
        this.transitionOps = transitionOps;
        this.unassigned = unassigned != null ? List.copyOf(unassigned) : List.of();
        this.excludedReasons = this.excluded.stream()
                .map(task -> new TaskDisposition(task, "OVERWEIGHT")).toList();
    }

    public Map<AnalystTeam, List<MitigationTask>> getAssignments() {
        return Collections.unmodifiableMap(assignments);
    }

    public int getTotalValue() {
        return totalValue;
    }

    public List<MitigationTask> getExcluded() {
        return Collections.unmodifiableList(excluded);
    }

    public long getStateCount() {
        return stateCount;
    }

    public long getTransitionOps() {
        return transitionOps;
    }

    public List<TaskDisposition> getUnassigned() {
        return unassigned;
    }

    public List<TaskDisposition> getExcludedReasons() {
        return excludedReasons;
    }

    @Override
    public String toString() {
        return "AssignmentResult{" +
                "assignmentsCount=" + assignments.size() +
                ", totalValue=" + totalValue +
                ", excludedCount=" + excluded.size() +
                ", stateCount=" + stateCount +
                '}';
    }
}
