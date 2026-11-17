package pk.ooad.ntd.service;

import pk.ooad.ntd.domain.AssignmentResult;
import pk.ooad.ntd.domain.MitigationTask;
import pk.ooad.ntd.response.AgeGrowth;
import pk.ooad.ntd.response.AlgorithmTimer;
import pk.ooad.ntd.response.AssignmentSolver;
import pk.ooad.ntd.response.AssignmentSolverFactory;
import pk.ooad.ntd.scenario.Scenario;

import java.util.List;

public final class AssignmentService {
    public record AssignmentRun(double ageGrowthRate, List<MitigationTask> tasks,
                                AlgorithmTimer.Measurement<AssignmentResult> assignment) {
        public AssignmentRun {
            tasks = List.copyOf(tasks);
        }
    }

    public record Comparison(double ageGrowthRate, List<MitigationTask> tasks,
                             AlgorithmTimer.Measurement<AssignmentResult> dp,
                             AlgorithmTimer.Measurement<AssignmentResult> greedy,
                             AlgorithmTimer.Measurement<AssignmentResult> bruteForce,
                             String bruteForceError) {
        public Comparison {
            tasks = List.copyOf(tasks);
        }
    }

    public AssignmentRun assign(Scenario scenario) {
        double rate = AgeGrowth.configuredRate();
        List<MitigationTask> tasks = grownTasks(scenario, rate);
        var teams = scenario.toTeams();
        AssignmentSolver solver = AssignmentSolverFactory.create(AssignmentSolverFactory.Solver.DP);
        return new AssignmentRun(rate, tasks, AlgorithmTimer.measure(() -> solver.planDaa(tasks, teams)));
    }

    public Comparison compare(Scenario scenario) {
        double rate = AgeGrowth.configuredRate();
        List<MitigationTask> tasks = grownTasks(scenario, rate);
        var teams = scenario.toTeams();
        AssignmentSolver dp = AssignmentSolverFactory.create(AssignmentSolverFactory.Solver.DP);
        AssignmentSolver greedy = AssignmentSolverFactory.create(AssignmentSolverFactory.Solver.GREEDY);
        var exact = AlgorithmTimer.measure(() -> dp.planDaa(tasks, teams));
        var approximate = AlgorithmTimer.measure(() -> greedy.planDaa(tasks, teams));
        AlgorithmTimer.Measurement<AssignmentResult> brute = null;
        String unavailable = null;
        if (tasks.size() <= 12) {
            AssignmentSolver bruteSolver = AssignmentSolverFactory.create(AssignmentSolverFactory.Solver.BRUTE_FORCE);
            brute = AlgorithmTimer.measure(() -> bruteSolver.planDaa(tasks, teams));
        } else {
            unavailable = "Brute force supports at most 12 submitted tasks; received " + tasks.size();
        }
        return new Comparison(rate, tasks, exact, approximate, brute, unavailable);
    }

    private static List<MitigationTask> grownTasks(Scenario scenario, double rate) {
        var network = scenario.toNetwork();
        return scenario.toTasks(network).stream().map(task -> AgeGrowth.apply(task, rate)).toList();
    }
}
