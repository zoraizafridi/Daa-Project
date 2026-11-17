package pk.ooad.ntd.response;

import java.util.Objects;

public final class AssignmentSolverFactory {
    public enum Solver { DP, GREEDY, BRUTE_FORCE }

    private AssignmentSolverFactory() {
    }

    public static AssignmentSolver create(Solver solver) {
        return switch (Objects.requireNonNull(solver, "solver must not be null")) {
            case DP -> new AssignmentPlanner();
            case GREEDY -> new GreedyRatioAssignment();
            case BRUTE_FORCE -> new BruteForceAssignment();
        };
    }
}
