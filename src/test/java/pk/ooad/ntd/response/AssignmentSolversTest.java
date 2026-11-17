package pk.ooad.ntd.response;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pk.ooad.ntd.domain.AnalystTeam;
import pk.ooad.ntd.domain.AssignmentResult;
import pk.ooad.ntd.domain.MitigationTask;
import pk.ooad.ntd.domain.Server;
import pk.ooad.ntd.domain.TaskDisposition;
import pk.ooad.ntd.response.AssignmentSolverFactory.Solver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssignmentSolversTest {
    private static final String STATE_LIMIT_PROPERTY = "ntd.dp.max.states";
    private static final Server TARGET = new Server("target", "Target", "10.0.0.1", 1);
    private String originalStateLimit;

    @BeforeEach
    void setStateLimit() {
        originalStateLimit = System.getProperty(STATE_LIMIT_PROPERTY);
        System.setProperty(STATE_LIMIT_PROPERTY, "2000000");
    }

    @AfterEach
    void restoreStateLimit() {
        if (originalStateLimit == null) System.clearProperty(STATE_LIMIT_PROPERTY);
        else System.setProperty(STATE_LIMIT_PROPERTY, originalStateLimit);
    }

    @Test
    void factoryReturnsFreshImplementationsAndRejectsInvalidModes() {
        assertInstanceOf(AssignmentPlanner.class, AssignmentSolverFactory.create(Solver.DP));
        assertInstanceOf(GreedyRatioAssignment.class, AssignmentSolverFactory.create(Solver.GREEDY));
        assertInstanceOf(BruteForceAssignment.class, AssignmentSolverFactory.create(Solver.BRUTE_FORCE));
        for (Solver mode : Solver.values()) {
            assertNotSame(AssignmentSolverFactory.create(mode), AssignmentSolverFactory.create(mode));
        }
        assertThrows(NullPointerException.class, () -> AssignmentSolverFactory.create(null));
        assertThrows(IllegalArgumentException.class, () -> Solver.valueOf("UNKNOWN"));
    }

    @Test
    void allocatedCellsIncludeBaseLayerAndTransitionsIncludeFailedTeamChecks() {
        List<AnalystTeam> teams = teams(1, 2, 3);
        List<MitigationTask> tasks = List.of(task("a", 10, 3), task("b", 9, 3), task("overweight", 100, 4));
        AssignmentResult dp = new AssignmentPlanner().planDaa(tasks, teams);
        assertEquals(72L, dp.getStateCount());
        assertEquals(144L, dp.getTransitionOps());
        assertEquals(10, dp.getTotalValue());
        assertPartition(tasks, teams, dp);

        AssignmentResult brute = new BruteForceAssignment().planDaa(tasks, teams);
        assertEquals(0L, brute.getStateCount());
        assertEquals(9L, brute.getTransitionOps());
        assertEquals(dp.getTotalValue(), brute.getTotalValue());
        assertPartition(tasks, teams, brute);

        AssignmentResult greedy = new GreedyRatioAssignment().planDaa(tasks, teams);
        assertEquals(0L, greedy.getStateCount());
        assertEquals(6L, greedy.getTransitionOps());
        assertPartition(tasks, teams, greedy);

        AssignmentResult generic = new AssignmentPlanner().plan(
                List.of(task("a", 11, 2), task("b", 19, 3)), List.of(team("only", 3)));
        assertEquals(12L, generic.getStateCount());
        assertEquals(8L, generic.getTransitionOps());
    }

    @Test
    void guardRejectsTablesPreviouslyAcceptedWhenBaseLayerWasNotCounted() {
        System.setProperty(STATE_LIMIT_PROPERTY, "8");
        IllegalArgumentException generic = assertThrows(IllegalArgumentException.class,
                () -> new AssignmentPlanner().plan(List.of(task("a", 1, 1), task("b", 1, 1)),
                        List.of(team("only", 3))));
        assertTrue(generic.getMessage().contains("12"));
        assertTrue(generic.getMessage().contains("dp.max.states"));

        System.setProperty(STATE_LIMIT_PROPERTY, "24");
        IllegalArgumentException strict = assertThrows(IllegalArgumentException.class,
                () -> new AssignmentPlanner().planDaa(List.of(task("a", 1, 1)), teams(1, 2, 3)));
        assertTrue(strict.getMessage().contains("48"));
        System.setProperty(STATE_LIMIT_PROPERTY, "48");
        assertEquals(48L, new AssignmentPlanner().planDaa(List.of(task("a", 1, 1)), teams(1, 2, 3)).getStateCount());
    }

    @Test
    void emptyEligibleStrictInputAllocatesBaseLayerAndHonorsGuardButGenericDoesNot() {
        List<AnalystTeam> teams = teams(1, 2, 3);
        List<MitigationTask> heavy = List.of(task("heavy", 100, 4));
        for (List<MitigationTask> tasks : List.of(List.<MitigationTask>of(), heavy)) {
            AssignmentResult result = new AssignmentPlanner().planDaa(tasks, teams);
            assertEquals(24L, result.getStateCount());
            assertEquals(0L, result.getTransitionOps());
            assertPartition(tasks, teams, result);
        }
        System.setProperty(STATE_LIMIT_PROPERTY, "23");
        assertThrows(IllegalArgumentException.class, () -> new AssignmentPlanner().planDaa(List.of(), teams));
        assertThrows(IllegalArgumentException.class, () -> new AssignmentPlanner().planDaa(heavy, teams));
        System.setProperty(STATE_LIMIT_PROPERTY, "0");
        assertThrows(IllegalArgumentException.class, () -> new AssignmentPlanner().planDaa(List.of(), teams));
        assertEquals(0L, new AssignmentPlanner().plan(List.of(), teams).getStateCount());
        assertEquals(0L, new AssignmentPlanner().plan(heavy, teams).getStateCount());
        for (Solver mode : List.of(Solver.GREEDY, Solver.BRUTE_FORCE)) {
            AssignmentResult result = AssignmentSolverFactory.create(mode).planDaa(heavy, teams);
            assertEquals(0L, result.getStateCount());
            assertEquals(0L, result.getTransitionOps());
            assertPartition(heavy, teams, result);
        }
    }

    @Test
    void everySolverHandlesNoTasksAndZeroValuesWithoutAssigningThem() {
        List<AnalystTeam> teams = teams(1, 2, 3);
        MitigationTask zero = task("zero", 0, 1);
        for (Solver mode : Solver.values()) {
            AssignmentSolver solver = AssignmentSolverFactory.create(mode);
            AssignmentResult empty = solver.planDaa(List.of(), teams);
            assertEquals(0, empty.getTotalValue());
            assertEquals(mode == Solver.DP ? 24L : 0L, empty.getStateCount());
            assertEquals(0L, empty.getTransitionOps());
            assertPartition(List.of(), teams, empty);
            AssignmentResult result = solver.planDaa(List.of(zero), teams);
            assertTrue(result.getAssignments().isEmpty());
            assertEquals(0, result.getTotalValue());
            assertEquals(List.of(new TaskDisposition(zero, "NON_POSITIVE_VALUE")), result.getUnassigned());
            assertEquals(mode == Solver.DP ? 72L : mode == Solver.BRUTE_FORCE ? 3L : 0L,
                    result.getTransitionOps());
            assertPartition(List.of(zero), teams, result);
        }
    }

    @Test
    void bruteForceAcceptsTwelveSubmittedTasksButRejectsThirteenBeforeOverweightFiltering() {
        List<MitigationTask> twelve = new ArrayList<>();
        for (int i = 1; i <= 12; i++) twelve.add(task("task-" + i, i, 2));
        List<AnalystTeam> teams = teams(1, 2, 3);
        AssignmentResult result = new BruteForceAssignment().planDaa(twelve, teams);
        assertEquals(23, result.getTotalValue());
        assertEquals(new AssignmentPlanner().planDaa(twelve, teams).getTotalValue(), result.getTotalValue());
        assertEquals(0L, result.getStateCount());
        assertTrue(result.getTransitionOps() > 0);
        assertPartition(twelve, teams, result);

        List<MitigationTask> oversized = new ArrayList<>();
        for (int i = 0; i < 13; i++) oversized.add(task("heavy-" + i, 1, 4));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new BruteForceAssignment().planDaa(oversized, teams));
        assertTrue(error.getMessage().contains("12 submitted tasks"));
        List<MitigationTask> twelveHeavy = oversized.subList(0, 12);
        AssignmentResult excluded = new BruteForceAssignment().planDaa(twelveHeavy, teams);
        assertEquals(12, excluded.getExcluded().size());
        assertEquals(0L, excluded.getTransitionOps());
        assertPartition(twelveHeavy, teams, excluded);
    }

    @Test
    void allStrictSolversRejectInvalidIdsCapacitiesEffortValuesDaysAndTeamCounts() {
        List<AnalystTeam> validTeams = teams(1, 2, 3);
        for (Solver mode : Solver.values()) {
            AssignmentSolver solver = AssignmentSolverFactory.create(mode);
            assertThrows(NullPointerException.class, () -> solver.planDaa(null, validTeams));
            assertThrows(NullPointerException.class, () -> solver.planDaa(List.of(), null));
            assertThrows(NullPointerException.class,
                    () -> solver.planDaa(Arrays.asList(task("a", 1, 1), null), validTeams));
            assertThrows(NullPointerException.class,
                    () -> solver.planDaa(List.of(), Arrays.asList(team("a", 1), null, team("c", 3))));
            for (List<AnalystTeam> invalid : List.of(List.<AnalystTeam>of(), teams(1), teams(1, 2),
                    teams(1, 2, 3, 4, 5), teams(0, 2, 3), teams(-1, 2, 3), teams(1, 1, 3),
                    List.of(team("duplicate", 1), team("duplicate", 2), team("other", 3)),
                    List.of(team(" ", 1), team("b", 2), team("c", 3)),
                    List.of(team("", 1), team("b", 2), team("c", 3)))) {
                assertThrows(IllegalArgumentException.class, () -> solver.planDaa(List.of(), invalid), mode.name());
            }
            for (List<MitigationTask> invalid : List.of(List.of(task("", 1, 1)), List.of(task(" \t", 1, 1)),
                    List.of(task("duplicate", 1, 1), task("duplicate", 1, 4)),
                    List.of(task("duplicate-heavy", 1, 4), task("duplicate-heavy", 2, 5)),
                    List.of(task("zero-effort", 1, 0)), List.of(task("negative-effort", 1, -1)),
                    List.of(task("negative-value", -1, 1)), List.of(task("negative-heavy", -1, 4)),
                    List.of(new MitigationTask("negative-days", TARGET, 1, 1, "ignored", -1)))) {
                assertThrows(IllegalArgumentException.class, () -> solver.planDaa(invalid, validTeams), mode.name());
            }
        }
    }

    @Test
    void genericPlannerRejectsDuplicateIdsBeforeFilteringAndPreservesZeroEffortAndCapacities() {
        AssignmentPlanner planner = new AssignmentPlanner();
        assertThrows(IllegalArgumentException.class,
                () -> planner.plan(List.of(task("same", 1, 99), task("same", 2, 100)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(List.of(),
                List.of(team("same", 1), team("same", 2))));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(List.of(), List.of(team("negative", -1))));
        AnalystTeam zeroCapacity = team("zero-capacity", 0);
        List<MitigationTask> tasks = List.of(task("a", 7, 0), task("b", 9, 0), task("zero", 0, 0));
        AssignmentResult result = planner.plan(tasks, List.of(zeroCapacity));
        assertEquals(16, result.getTotalValue());
        assertEquals(4L, result.getStateCount());
        assertEquals(3L, result.getTransitionOps());
        assertPartition(tasks, List.of(zeroCapacity), result);
    }

    @Test
    void checkedValueAdditionAndFlatCapacityIndexingNeverSilentlyOverflow() {
        List<MitigationTask> overflow = List.of(task("a", Integer.MAX_VALUE, 1), task("b", 1, 1));
        for (Solver mode : Solver.values()) {
            assertThrows(ArithmeticException.class,
                    () -> AssignmentSolverFactory.create(mode).planDaa(overflow, teams(1, 2, 3)), mode.name());
        }
        assertThrows(IllegalArgumentException.class, () -> new AssignmentPlanner().plan(
                List.of(task("a", 1, 1)), List.of(team("huge", Integer.MAX_VALUE))));
        assertThrows(IllegalArgumentException.class, () -> new AssignmentPlanner().planDaa(
                List.of(), teams(Integer.MAX_VALUE, Integer.MAX_VALUE - 1, Integer.MAX_VALUE - 2)));
        System.setProperty(STATE_LIMIT_PROPERTY, Integer.toString(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new AssignmentPlanner().plan(
                List.of(task("a", 1, 1), task("b", 1, 1)), List.of(team("huge", 1000000000))));
    }

    @Test
    void dpRetainsTaskIdSortingSkipBeforeTeamTiesAndInputTeamOrderWithoutSkillChecks() {
        List<AnalystTeam> teams = List.of(team("z-first", 1), team("a-second", 2), team("m-third", 3));
        MitigationTask single = task("single", 10, 1);
        AssignmentResult one = new AssignmentPlanner().planDaa(List.of(single), teams);
        assertEquals(List.of(single), one.getAssignments().get(teams.get(0)));
        assertEquals(1, one.getAssignments().size());
        List<MitigationTask> tasks = List.of(task("b", 9, 3), task("zero", 0, 1), task("a", 9, 3));
        AssignmentResult first = new AssignmentPlanner().planDaa(tasks, teams);
        List<MitigationTask> reversed = new ArrayList<>(tasks);
        Collections.reverse(reversed);
        AssignmentResult second = new AssignmentPlanner().planDaa(reversed, teams);
        assertEquals(List.of(tasks.get(2)), first.getAssignments().get(teams.get(2)));
        assertEquals(first.getAssignments(), second.getAssignments());
        assertPartition(tasks, teams, first);
        for (Solver mode : Solver.values()) {
            AssignmentResult result = AssignmentSolverFactory.create(mode).planDaa(List.of(single), teams);
            assertEquals(10, result.getTotalValue());
            assertPartition(List.of(single), teams, result);
        }
    }

    @Test
    void greedyUsesExactLongRatiosIdTiesAndFirstFitInInputTeamOrder() {
        List<AnalystTeam> teams = List.of(team("z-first", 2), team("a-second", 3), team("m-third", 4));
        MitigationTask a = task("a", 10, 2);
        MitigationTask b = task("b", 10, 2);
        AssignmentResult tied = new GreedyRatioAssignment().planDaa(List.of(b, a), teams);
        assertEquals(List.of(a), tied.getAssignments().get(teams.get(0)));
        assertEquals(List.of(b), tied.getAssignments().get(teams.get(1)));
        assertEquals(3L, tied.getTransitionOps());
        assertPartition(List.of(b, a), teams, tied);

        int max = Integer.MAX_VALUE;
        MitigationTask lowerRatio = task("a", max - 2, max - 1);
        MitigationTask higherRatio = task("z", max - 1, max);
        List<MitigationTask> closeRatios = List.of(lowerRatio, higherRatio);
        List<AnalystTeam> largeTeams = teams(max, 1, 2);
        System.setProperty(STATE_LIMIT_PROPERTY, "0");
        AssignmentResult exact = new GreedyRatioAssignment().planDaa(closeRatios, largeTeams);
        assertEquals(max - 1, exact.getTotalValue());
        assertEquals(List.of(higherRatio), exact.getAssignments().get(largeTeams.get(0)));
        assertEquals(4L, exact.getTransitionOps());
        assertEquals(exact.getTotalValue(), new BruteForceAssignment().planDaa(closeRatios, largeTeams).getTotalValue());
        assertPartition(closeRatios, largeTeams, exact);
    }

    @Test
    void preGrownMainFixtureHasExactValue290WithoutGrowthOrInputMutation() {
        List<AnalystTeam> teams = teams(6, 4, 3);
        List<MitigationTask> grown = List.of(task("task-host-01", 100, 4), task("task-host-02", 70, 3),
                task("task-host-03", 30, 2),
                new MitigationTask("task-host-10", TARGET, 120, 6, "ignored", 2),
                task("task-host-11", 40, 3));
        AssignmentResult dp = new AssignmentPlanner().planDaa(grown, teams);
        AssignmentResult brute = new BruteForceAssignment().planDaa(grown, teams);
        AssignmentResult greedy = new GreedyRatioAssignment().planDaa(grown, teams);
        assertEquals(290, dp.getTotalValue());
        assertEquals(290, brute.getTotalValue());
        assertEquals(240, greedy.getTotalValue());
        assertEquals(Set.of("task-host-01", "task-host-02", "task-host-10"), assignedIds(dp));
        assertEquals(840L, dp.getStateCount());
        assertEquals(2100L, dp.getTransitionOps());
        for (AssignmentResult result : List.of(dp, brute, greedy)) assertPartition(grown, teams, result);
        assertEquals(List.of(100, 70, 30, 120, 40), grown.stream().map(MitigationTask::getValue).toList());
        assertEquals(List.of(6, 4, 3), teams.stream().map(AnalystTeam::getCapacity).toList());
        assertEquals(2, grown.get(3).getDaysUnpatched());
    }

    @Test
    void resultContainersAreImmutableSnapshotsAndLegacyConstructorRemainsCompatible() {
        AnalystTeam team = team("team", 3);
        MitigationTask assigned = task("assigned", 10, 2);
        MitigationTask heavy = task("heavy", 100, 4);
        MitigationTask skipped = task("skipped", 0, 1);
        List<MitigationTask> assignedList = new ArrayList<>(List.of(assigned));
        Map<AnalystTeam, List<MitigationTask>> assignments = new LinkedHashMap<>();
        assignments.put(team, assignedList);
        List<MitigationTask> excluded = new ArrayList<>(List.of(heavy));
        List<TaskDisposition> unassigned = new ArrayList<>(List.of(new TaskDisposition(skipped, "NON_POSITIVE_VALUE")));
        AssignmentResult result = new AssignmentResult(assignments, 10, excluded, 12L, 8L, unassigned);
        AssignmentResult legacy = new AssignmentResult(assignments, 10, excluded, 12L);
        assignedList.clear();
        assignments.clear();
        excluded.clear();
        unassigned.clear();
        assertEquals(List.of(assigned), result.getAssignments().get(team));
        assertEquals(List.of(assigned), legacy.getAssignments().get(team));
        assertEquals(List.of(heavy), result.getExcluded());
        assertEquals(List.of(new TaskDisposition(heavy, "OVERWEIGHT")), result.getExcludedReasons());
        assertEquals(List.of(new TaskDisposition(skipped, "NON_POSITIVE_VALUE")), result.getUnassigned());
        assertEquals(12L, result.getStateCount());
        assertEquals(8L, result.getTransitionOps());
        assertEquals(0L, legacy.getTransitionOps());
        assertTrue(legacy.getUnassigned().isEmpty());
        assertEquals(result.getExcludedReasons(), legacy.getExcludedReasons());
        for (AssignmentResult snapshot : List.of(result, legacy)) {
            assertThrows(UnsupportedOperationException.class, () -> snapshot.getAssignments().put(team, List.of()));
            assertThrows(UnsupportedOperationException.class, () -> snapshot.getAssignments().get(team).add(skipped));
            assertThrows(UnsupportedOperationException.class, () -> snapshot.getExcluded().add(skipped));
            assertThrows(UnsupportedOperationException.class,
                    () -> snapshot.getExcludedReasons().add(new TaskDisposition(skipped, "OVERWEIGHT")));
            assertThrows(UnsupportedOperationException.class,
                    () -> snapshot.getUnassigned().add(new TaskDisposition(skipped, "NON_POSITIVE_VALUE")));
        }
        AssignmentResult nullLegacy = new AssignmentResult(null, 0, null, 0L);
        assertTrue(nullLegacy.getAssignments().isEmpty());
        assertTrue(nullLegacy.getExcluded().isEmpty());
        assertTrue(nullLegacy.getExcludedReasons().isEmpty());
    }

    @Test
    void twoHundredSeededThreeAndFourTeamCasesCompareProductionBruteDpAndGreedy() {
        Random random = new Random(32452843L);
        for (int caseIndex = 0; caseIndex < 200; caseIndex++) {
            List<Integer> capacityPool = new ArrayList<>(List.of(1, 2, 3, 4, 5, 6, 7, 8));
            Collections.shuffle(capacityPool, random);
            int teamCount = caseIndex % 2 == 0 ? 3 : 4;
            List<AnalystTeam> teams = new ArrayList<>();
            for (int i = 0; i < teamCount; i++) teams.add(team("team-" + i, capacityPool.get(i)));
            List<MitigationTask> tasks = new ArrayList<>();
            int count = random.nextInt(9);
            for (int i = 0; i < count; i++) {
                tasks.add(new MitigationTask("task-" + i, TARGET, random.nextInt(101),
                        1 + random.nextInt(10), "not-a-team-skill", random.nextInt(10)));
            }
            Collections.shuffle(tasks, random);
            AssignmentResult dp = new AssignmentPlanner().planDaa(tasks, teams);
            AssignmentResult brute = new BruteForceAssignment().planDaa(tasks, teams);
            AssignmentResult greedy = new GreedyRatioAssignment().planDaa(tasks, teams);
            assertEquals(brute.getTotalValue(), dp.getTotalValue(), "seeded case " + caseIndex);
            assertTrue(dp.getTotalValue() >= greedy.getTotalValue(), "seeded case " + caseIndex);
            int maxCapacity = teams.stream().mapToInt(AnalystTeam::getCapacity).max().orElseThrow();
            long eligible = tasks.stream().filter(task -> task.getCost() <= maxCapacity).count();
            long product = 1L;
            for (AnalystTeam team : teams) product *= (long) team.getCapacity() + 1L;
            assertEquals((eligible + 1L) * product, dp.getStateCount(), "seeded case " + caseIndex);
            assertEquals(eligible * product * teamCount, dp.getTransitionOps(), "seeded case " + caseIndex);
            assertEquals(0L, brute.getStateCount());
            assertEquals(0L, greedy.getStateCount());
            for (AssignmentResult result : List.of(dp, brute, greedy)) assertPartition(tasks, teams, result);
        }
    }

    private static void assertPartition(List<MitigationTask> tasks, List<AnalystTeam> teams, AssignmentResult result) {
        Set<String> inputIds = new HashSet<>();
        for (MitigationTask task : tasks) assertTrue(inputIds.add(task.getId()));
        Set<String> covered = new HashSet<>();
        long total = 0L;
        for (Map.Entry<AnalystTeam, List<MitigationTask>> entry : result.getAssignments().entrySet()) {
            assertTrue(teams.contains(entry.getKey()));
            assertFalse(entry.getValue().isEmpty());
            long effort = entry.getValue().stream().mapToLong(MitigationTask::getCost).sum();
            assertTrue(effort <= entry.getKey().getCapacity());
            List<String> ids = entry.getValue().stream().map(MitigationTask::getId).toList();
            assertEquals(ids.stream().sorted().toList(), ids);
            for (MitigationTask task : entry.getValue()) {
                assertTrue(covered.add(task.getId()), "task reused or in multiple groups: " + task.getId());
                total += task.getValue();
            }
        }
        int maxCapacity = teams.stream().mapToInt(AnalystTeam::getCapacity).max().orElse(-1);
        for (MitigationTask task : result.getExcluded()) {
            assertTrue(task.getCost() > maxCapacity);
            assertTrue(covered.add(task.getId()), "excluded task overlaps another group");
        }
        assertEquals(result.getExcluded().stream().map(task -> new TaskDisposition(task, "OVERWEIGHT")).toList(),
                result.getExcludedReasons());
        for (TaskDisposition disposition : result.getUnassigned()) {
            assertTrue(disposition.task().getCost() <= maxCapacity);
            assertTrue(covered.add(disposition.task().getId()), "unassigned task overlaps another group");
            assertEquals(disposition.task().getValue() <= 0 ? "NON_POSITIVE_VALUE" : "UNASSIGNED_CAPACITY",
                    disposition.reason());
        }
        assertEquals(inputIds, covered, "assigned, excluded, unassigned must exactly cover input");
        assertEquals((long) result.getTotalValue(), total);
    }

    private static Set<String> assignedIds(AssignmentResult result) {
        Set<String> ids = new HashSet<>();
        result.getAssignments().values().forEach(list -> list.forEach(task -> assertTrue(ids.add(task.getId()))));
        return ids;
    }

    private static MitigationTask task(String id, int value, int effort) {
        return new MitigationTask(id, TARGET, value, effort, "ignored");
    }

    private static AnalystTeam team(String id, int capacity) {
        return new AnalystTeam(id, id, Set.of(), capacity, false);
    }

    private static List<AnalystTeam> teams(int... capacities) {
        List<AnalystTeam> teams = new ArrayList<>();
        for (int i = 0; i < capacities.length; i++) teams.add(team("team-" + i, capacities[i]));
        return teams;
    }
}
