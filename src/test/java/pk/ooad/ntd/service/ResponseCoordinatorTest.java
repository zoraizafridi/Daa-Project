package pk.ooad.ntd.service;

import org.junit.jupiter.api.Test;
import pk.ooad.ntd.domain.AssetStatus;
import pk.ooad.ntd.domain.Link;
import pk.ooad.ntd.domain.MitigationTask;
import pk.ooad.ntd.domain.Network;
import pk.ooad.ntd.domain.NetworkAsset;
import pk.ooad.ntd.domain.ResponsePlan;
import pk.ooad.ntd.scenario.Scenario;
import pk.ooad.ntd.scenario.ScenarioLoader;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseCoordinatorTest {
    @Test
    void mainFixtureGrowsOnceAndReportsExactBackboneAndAssignmentChanges() {
        Scenario scenario = ScenarioLoader.load("main-12-hosts-22-links");
        SensitivityService.Report report = new SensitivityService().analyze(scenario);
        assertEquals(List.of("host-10", "host-11"), hostIds(report.removedHosts()));
        assertSame(scenario, report.before().scenario());
        assertSame(scenario, report.after().scenario());
        assertEquals(0.1, report.before().ageGrowthRate());
        assertEquals(66.0, report.before().backbone().result().getTotalWeight());
        assertEquals(45.0, report.after().backbone().result().getTotalWeight());
        assertEquals(290, report.before().assignment().result().getTotalValue());
        assertEquals(200, report.after().assignment().result().getTotalValue());
        assertEquals(-21.0, report.costDelta());
        assertEquals(-90, report.valueDelta());
        assertEquals(List.of("task-host-01", "task-host-02", "task-host-10"), assignedIds(report.before()));
        assertEquals(List.of("task-host-01", "task-host-02", "task-host-03"), assignedIds(report.after()));
        assertEquals(120, findTask(report.before(), "task-host-10").getValue());
        assertEquals(100, scenario.tasks().stream().filter(task -> task.id().equals("task-host-10"))
                .findFirst().orElseThrow().baseValue());
        assertEquals(List.of("chain-10", "chain-11"), report.removedLinkIds());
        assertEquals(List.of(), report.addedLinkIds());
        assertEquals(List.of("task-host-03"), report.newlyAssignedTaskIds());
        assertEquals(List.of("task-host-10"), report.noLongerAssignedTaskIds());
        // Task 11 was never assigned, but must still be reported as dropped after its host disappears.
        assertEquals(Map.of("task-host-10", "MISSING_HOST", "task-host-11", "MISSING_HOST"),
                droppedReasons(report.after()));
        assertEquals(report.after().droppedTasks(), report.droppedTasks());
        assertEquals(List.of(), report.newlyIsolatedHosts());
        assertEquals(12, report.before().activeHosts().size());
        assertEquals(10, report.after().activeHosts().size());
        assertEquals(22, report.before().activeLinks().size());
        assertEquals(18, report.after().activeLinks().size());
        assertTrue(report.after().activeLinks().stream().noneMatch(edge ->
                Set.of("host-10", "host-11").contains(edge.sourceId())
                        || Set.of("host-10", "host-11").contains(edge.targetId())));
        assertTrue(report.before().backbone().result().isConnected());
        assertTrue(report.after().backbone().result().isConnected());
        assertEquals(1, report.before().backbone().warmupRuns());
        assertEquals(5, report.before().backbone().measuredRuns());
        assertEquals(1, report.after().assignment().warmupRuns());
        assertEquals(5, report.after().assignment().measuredRuns());
        assertTrue(report.before().backbone().medianNanos() >= 0);
        assertTrue(report.after().assignment().medianNanos() >= 0);
        assertAssignmentDiffs(report);
    }

    @Test
    void independentCopiesLeaveOriginalNetworkScenarioAndLiveLastPlanUntouched() throws Exception {
        Scenario scenario = ScenarioLoader.load("main-12-hosts-22-links");
        Network original = scenario.toNetwork();
        original.getAsset("host-04").addRisk(20);
        original.getAsset("host-04").setStatus(AssetStatus.SUSPICIOUS);
        original.getLinks().stream().filter(link -> link.getId().equals("extra-03"))
                .findFirst().orElseThrow().markCompromised();
        IncidentResponseFacade facade = new IncidentResponseFacade(original, 19L,
                scenario.toTasks(original), scenario.toTeams());
        try {
            ResponsePlan livePlan = facade.runResponse();
            Field lastPlan = IncidentResponseFacade.class.getDeclaredField("lastResponsePlan");
            lastPlan.setAccessible(true);
            assertSame(livePlan, lastPlan.get(facade));
            List<?> originalSnapshot = networkSnapshot(original);
            List<?> livePlanSnapshot = List.of(
                    livePlan.getBackbone().getChosenLinks().stream().map(Link::getId).toList(),
                    livePlan.getBackbone().getTotalWeight(), livePlan.getAssignment().getTotalValue());

            ResponseCoordinator.Report single = new ResponseCoordinator().run(scenario);
            SensitivityService.Report comparison = new SensitivityService().analyze(scenario);
            assertEquals(originalSnapshot, networkSnapshot(original));
            assertSame(original, facade.getNetwork());
            assertSame(livePlan, lastPlan.get(facade));
            assertEquals(livePlanSnapshot, List.of(
                    livePlan.getBackbone().getChosenLinks().stream().map(Link::getId).toList(),
                    livePlan.getBackbone().getTotalWeight(), livePlan.getAssignment().getTotalValue()));
            assertEquals(scenario, ScenarioLoader.load(scenario.id()));
            NetworkAsset singleHost = findTask(single, "task-host-01").getTarget();
            NetworkAsset beforeHost = findTask(comparison.before(), "task-host-01").getTarget();
            NetworkAsset afterHost = findTask(comparison.after(), "task-host-01").getTarget();
            assertNotSame(original.getAsset("host-01"), singleHost);
            assertNotSame(singleHost, beforeHost);
            assertNotSame(beforeHost, afterHost);
            var beforeTeam = comparison.before().assignment().result().getAssignments().keySet().iterator().next();
            comparison.after().assignment().result().getAssignments().keySet()
                    .forEach(team -> assertNotSame(beforeTeam, team));
            beforeHost.addRisk(40);
            beforeHost.setStatus(AssetStatus.COMPROMISED);
            comparison.before().backbone().result().getChosenLinks().get(0).markCompromised();
            assertEquals(1.0, afterHost.getRiskScore());
            assertEquals(AssetStatus.NORMAL, afterHost.getStatus());
            assertEquals(originalSnapshot, networkSnapshot(original));
            assertEquals(scenario, ScenarioLoader.load(scenario.id()));
        } finally {
            facade.shutdown();
        }
    }

    @Test
    void selectsByRiskThenCriticalityThenIdWhileNeverSelectingSoc() {
        Scenario scenario = scenario(List.of(
                host("soc", 10, 10), host("a", 8, 10), host("d", 9, 7),
                host("z", 9, 1), host("c", 9, 7), host("b", 9, 7)), List.of(), List.of());
        SensitivityService.Report report = new SensitivityService().analyze(scenario);
        assertEquals(List.of("b", "c"), hostIds(report.removedHosts()));
        assertTrue(hostIds(report.after().activeHosts()).contains("soc"));
    }

    @Test
    void handlesOneNonSocHostOrOnlyTheSoc() {
        Scenario pair = scenario(List.of(host("soc", 10, 10), host("only", 1, 1)),
                List.of(edge("soc-only", "soc", "only", 2)),
                List.of(task("only-task", "only", 1, 5, 0)));
        SensitivityService.Report pairReport = new SensitivityService().analyze(pair);
        assertEquals(List.of("only"), hostIds(pairReport.removedHosts()));
        assertEquals(List.of("soc"), hostIds(pairReport.after().activeHosts()));
        assertEquals(Map.of("only-task", "MISSING_HOST"), droppedReasons(pairReport.after()));
        assertEquals(0, pairReport.after().assignment().result().getTotalValue());
        assertTrue(pairReport.after().backbone().result().isConnected());

        Scenario solo = scenario(List.of(host("soc", 10, 10)), List.of(),
                List.of(task("soc-task", "soc", 1, 5, 0)));
        SensitivityService.Report soloReport = new SensitivityService().analyze(solo);
        assertEquals(List.of(), soloReport.removedHosts());
        assertEquals(0.0, soloReport.costDelta());
        assertEquals(0, soloReport.valueDelta());
        assertEquals(List.of("soc-task"), assignedIds(soloReport.after()));
        assertEquals(responseSnapshot(soloReport.before()), responseSnapshot(soloReport.after()));
        assertNotSame(findTask(soloReport.before(), "soc-task").getTarget(),
                findTask(soloReport.after(), "soc-task").getTarget());
    }

    @Test
    void filtersDisconnectedTasksAndDoesNotCallThemNewlyIsolated() {
        Scenario scenario = ScenarioLoader.load("disconnected");
        ResponseCoordinator.Report response = new ResponseCoordinator().run(scenario);
        assertFalse(response.backbone().result().isConnected());
        assertEquals(List.of("isolated", "remote-a", "remote-b"), unreachableIds(response));
        assertEquals(List.of("task-reachable"), response.tasks().stream().map(MitigationTask::getId).toList());
        assertEquals(25, response.assignment().result().getTotalValue());
        assertEquals(Map.of("task-isolated", "OUTSIDE_SOC_COMPONENT", "task-remote", "OUTSIDE_SOC_COMPONENT"),
                droppedReasons(response));
        SensitivityService.Report comparison = new SensitivityService().analyze(scenario);
        assertEquals(List.of("isolated", "remote-a"), hostIds(comparison.removedHosts()));
        assertEquals(List.of(), comparison.newlyIsolatedHosts());
        assertEquals(Map.of("task-isolated", "MISSING_HOST", "task-remote", "MISSING_HOST"),
                droppedReasons(comparison.after()));
    }

    @Test
    void removingBridgeDropsPresentTasksOutsideSocAndExcludesPreviouslyUnreachableHosts() {
        Scenario scenario = scenario(List.of(host("soc", 10, 10), host("bridge", 9, 5),
                host("cut", 8, 5), host("leaf", 1, 1), host("old-isolated", 1, 1)),
                List.of(edge("soc-bridge", "soc", "bridge", 1),
                        edge("bridge-leaf", "bridge", "leaf", 2), edge("soc-cut", "soc", "cut", 4)),
                List.of(task("task-bridge", "bridge", 1, 10, 1),
                        task("task-leaf", "leaf", 1, 20, 0), task("task-old", "old-isolated", 1, 30, 1)));
        SensitivityService.Report comparison = new SensitivityService().analyze(scenario);
        assertEquals(List.of("bridge", "cut"), hostIds(comparison.removedHosts()));
        assertEquals(List.of("leaf"), hostIds(comparison.newlyIsolatedHosts()));
        assertTrue(hostIds(comparison.after().activeHosts()).contains("leaf"));
        assertEquals(Map.of("task-bridge", "MISSING_HOST", "task-leaf", "OUTSIDE_SOC_COMPONENT",
                "task-old", "OUTSIDE_SOC_COMPONENT"), droppedReasons(comparison.after()));
        assertEquals(List.of("task-bridge", "task-leaf", "task-old"),
                comparison.droppedTasks().stream().map(drop -> drop.task().id()).toList());
        assertEquals(List.of("task-bridge", "task-leaf"), comparison.noLongerAssignedTaskIds());
        assertEquals(List.of(), comparison.after().tasks());
        assertAssignmentDiffs(comparison);
    }

    @Test
    void filtersMissingAndCompromisedOnlyTargetsBeforeApplyingGrowth() {
        Scenario scenario = scenario(List.of(host("soc", 1, 5), host("removed", 9, 5), host("remote", 2, 2)),
                List.of(new Scenario.Edge("blocked", "soc", "remote", 1, true)),
                List.of(task("missing-task", "removed", 1, Integer.MAX_VALUE, Integer.MAX_VALUE),
                        task("remote-task", "remote", 1, Integer.MAX_VALUE, Integer.MAX_VALUE)));
        ResponseCoordinator.Report response = new ResponseCoordinator().run(scenario, Set.of("removed"));
        assertEquals(Map.of("missing-task", "MISSING_HOST", "remote-task", "OUTSIDE_SOC_COMPONENT"),
                droppedReasons(response));
        assertEquals(List.of(), response.tasks());
        assertEquals(0, response.assignment().result().getTotalValue());
        assertEquals(1, response.activeLinks().size());
        assertEquals(List.of(), response.backbone().result().getChosenLinks());
    }

    @Test
    void chosenBackboneDiffsIncludeReplacementsButNotUnchosenIncidentLinks() {
        Scenario scenario = scenario(List.of(host("soc", 1, 5), host("hub", 10, 5),
                host("removed", 9, 5), host("a", 1, 1), host("c", 1, 1)),
                List.of(edge("hub-soc", "hub", "soc", 1), edge("hub-a", "hub", "a", 2),
                        edge("hub-c", "hub", "c", 3), edge("soc-removed", "soc", "removed", 4),
                        edge("a-c", "a", "c", 5), edge("soc-a", "soc", "a", 6),
                        edge("soc-c", "soc", "c", 7), edge("hub-removed", "hub", "removed", 8)),
                List.of());
        SensitivityService.Report comparison = new SensitivityService().analyze(scenario);
        assertEquals(List.of("hub-a", "hub-c", "hub-soc", "soc-removed"), comparison.removedLinkIds());
        assertEquals(List.of("a-c", "soc-a"), comparison.addedLinkIds());
        assertEquals(1.0, comparison.costDelta());
        assertEquals(3, comparison.after().activeLinks().size());
        assertFalse(comparison.after().activeLinks().stream().anyMatch(edge -> edge.id().equals("hub-removed")));
        assertFalse(comparison.removedLinkIds().contains("hub-removed"));
    }

    @Test
    void rejectsUnknownNullAndSocRemovalIdsWithoutChangingInputs() {
        Scenario scenario = ScenarioLoader.load("main-12-hosts-22-links");
        ResponseCoordinator coordinator = new ResponseCoordinator();
        assertThrows(IllegalArgumentException.class, () -> coordinator.run(scenario, Set.of("unknown")));
        assertThrows(IllegalArgumentException.class, () -> coordinator.run(scenario, Set.of("soc")));
        assertThrows(IllegalArgumentException.class, () -> coordinator.run(scenario, Set.of("host-10", "soc")));
        assertThrows(IllegalArgumentException.class, () -> coordinator.run(scenario, null));
        Set<String> withNull = new HashSet<>();
        withNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> coordinator.run(scenario, withNull));
        assertThrows(IllegalArgumentException.class, () -> coordinator.run(null));
        assertThrows(IllegalArgumentException.class, () -> new SensitivityService().analyze(null));
        Set<String> removals = new HashSet<>(Set.of("host-10", "host-11"));
        coordinator.run(scenario, removals);
        assertEquals(Set.of("host-10", "host-11"), removals);
        assertEquals(scenario, ScenarioLoader.load(scenario.id()));
    }

    @Test
    void repeatedCallsAreDeterministicApartFromTimingAndDoNotAccumulateGrowthOrRemoval() {
        Scenario main = ScenarioLoader.load("main-12-hosts-22-links");
        ResponseCoordinator coordinator = new ResponseCoordinator();
        ResponseCoordinator.Report first = coordinator.run(main);
        coordinator.run(main, Set.of("host-10", "host-11"));
        coordinator.run(ScenarioLoader.load("disconnected"));
        ResponseCoordinator.Report repeated = coordinator.run(main);
        assertEquals(responseSnapshot(first), responseSnapshot(repeated));
        assertEquals(120, findTask(repeated, "task-host-10").getValue());
        SensitivityService sensitivity = new SensitivityService();
        SensitivityService.Report before = sensitivity.analyze(main);
        sensitivity.analyze(ScenarioLoader.load("disconnected"));
        SensitivityService.Report after = sensitivity.analyze(main);
        assertEquals(sensitivitySnapshot(before), sensitivitySnapshot(after));
        assertAssignmentDiffs(after);
    }

    @Test
    void reportListsAreDefensiveCopiesAndUnmodifiable() {
        ResponseCoordinator.Report response = new ResponseCoordinator().run(ScenarioLoader.load("disconnected"));
        var hosts = new ArrayList<>(response.activeHosts());
        var links = new ArrayList<>(response.activeLinks());
        var tasks = new ArrayList<>(response.tasks());
        var drops = new ArrayList<>(response.droppedTasks());
        var copy = new ResponseCoordinator.Report(response.scenario(), hosts, links, response.ageGrowthRate(),
                tasks, response.backbone(), response.assignment(), drops);
        hosts.clear();
        links.clear();
        tasks.clear();
        drops.clear();
        assertEquals(responseSnapshot(response), responseSnapshot(copy));
        assertThrows(UnsupportedOperationException.class, () -> copy.activeHosts().clear());
        assertThrows(UnsupportedOperationException.class, () -> copy.activeLinks().clear());
        assertThrows(UnsupportedOperationException.class, () -> copy.tasks().clear());
        assertThrows(UnsupportedOperationException.class, () -> copy.droppedTasks().clear());

        SensitivityService.Report comparison = new SensitivityService().analyze(ScenarioLoader.load("main-12-hosts-22-links"));
        var removedHosts = new ArrayList<>(comparison.removedHosts());
        var removedLinks = new ArrayList<>(comparison.removedLinkIds());
        var addedLinks = new ArrayList<>(comparison.addedLinkIds());
        var isolated = new ArrayList<>(comparison.newlyIsolatedHosts());
        var dropped = new ArrayList<>(comparison.droppedTasks());
        var newlyAssigned = new ArrayList<>(comparison.newlyAssignedTaskIds());
        var noLongerAssigned = new ArrayList<>(comparison.noLongerAssignedTaskIds());
        var moved = new ArrayList<>(comparison.movedTasks());
        var comparisonCopy = new SensitivityService.Report(removedHosts, comparison.before(), comparison.after(),
                removedLinks, addedLinks, comparison.costDelta(), comparison.valueDelta(), isolated,
                dropped, newlyAssigned, noLongerAssigned, moved);
        removedHosts.clear();
        removedLinks.clear();
        addedLinks.clear();
        isolated.clear();
        dropped.clear();
        newlyAssigned.clear();
        noLongerAssigned.clear();
        moved.clear();
        assertEquals(sensitivitySnapshot(comparison), sensitivitySnapshot(comparisonCopy));
        for (List<?> list : List.of(comparisonCopy.removedHosts(), comparisonCopy.removedLinkIds(),
                comparisonCopy.addedLinkIds(), comparisonCopy.newlyIsolatedHosts(), comparisonCopy.droppedTasks(),
                comparisonCopy.newlyAssignedTaskIds(), comparisonCopy.noLongerAssignedTaskIds(), comparisonCopy.movedTasks())) {
            assertThrows(UnsupportedOperationException.class, list::clear);
        }
    }

    private static void assertAssignmentDiffs(SensitivityService.Report report) {
        Map<String, String> before = assignedTeams(report.before());
        Map<String, String> after = assignedTeams(report.after());
        assertEquals(after.keySet().stream().filter(id -> !before.containsKey(id)).sorted().toList(),
                report.newlyAssignedTaskIds());
        assertEquals(before.keySet().stream().filter(id -> !after.containsKey(id)).sorted().toList(),
                report.noLongerAssignedTaskIds());
        List<SensitivityService.MovedTask> expectedMoves = before.entrySet().stream()
                .filter(entry -> after.containsKey(entry.getKey()) && !entry.getValue().equals(after.get(entry.getKey())))
                .map(entry -> new SensitivityService.MovedTask(entry.getKey(), entry.getValue(), after.get(entry.getKey())))
                .toList();
        assertEquals(expectedMoves, report.movedTasks());
    }

    private static List<?> responseSnapshot(ResponseCoordinator.Report report) {
        return List.of(report.scenario(), report.activeHosts(), report.activeLinks(), report.ageGrowthRate(),
                report.tasks().stream().map(task -> List.of(task.getId(), task.getTarget().getId(),
                        task.getValue(), task.getCost(), task.getDaysUnpatched())).toList(),
                report.backbone().result().getChosenLinks().stream().map(Link::getId).toList(),
                report.backbone().result().getTotalWeight(), report.backbone().result().isConnected(),
                unreachableIds(report), report.backbone().result().getOperations(), assignedTeams(report),
                report.assignment().result().getTotalValue(), report.assignment().result().getTransitionOps(),
                report.assignment().result().getUnassigned().stream().map(row -> row.task().getId()).sorted().toList(),
                report.droppedTasks());
    }

    private static List<?> sensitivitySnapshot(SensitivityService.Report report) {
        return List.of(report.removedHosts(), responseSnapshot(report.before()), responseSnapshot(report.after()),
                report.removedLinkIds(), report.addedLinkIds(), report.costDelta(), report.valueDelta(),
                report.newlyIsolatedHosts(), report.droppedTasks(), report.newlyAssignedTaskIds(),
                report.noLongerAssignedTaskIds(), report.movedTasks());
    }

    private static List<?> networkSnapshot(Network network) {
        return List.of(network.getAssets().stream().map(asset -> List.of(asset.getId(), asset.getName(),
                        asset.getRiskScore(), asset.getStatus(), asset.getCriticality())).toList(),
                network.getLinks().stream().map(link -> List.of(link.getId(), link.getSource().getId(),
                        link.getTarget().getId(), link.getBaseCost(), link.getStatus())).toList());
    }

    private static Map<String, String> assignedTeams(ResponseCoordinator.Report report) {
        Map<String, String> result = new TreeMap<>();
        report.assignment().result().getAssignments().forEach((team, tasks) ->
                tasks.forEach(task -> result.put(task.getId(), team.getId())));
        return result;
    }

    private static List<String> assignedIds(ResponseCoordinator.Report report) {
        return List.copyOf(assignedTeams(report).keySet());
    }

    private static Map<String, String> droppedReasons(ResponseCoordinator.Report report) {
        return report.droppedTasks().stream().collect(Collectors.toMap(drop -> drop.task().id(),
                ResponseCoordinator.DroppedTask::reason));
    }

    private static List<String> unreachableIds(ResponseCoordinator.Report report) {
        return report.backbone().result().getUnreachable().stream().map(NetworkAsset::getId).sorted().toList();
    }

    private static List<String> hostIds(List<Scenario.Host> hosts) {
        return hosts.stream().map(Scenario.Host::id).toList();
    }

    private static MitigationTask findTask(ResponseCoordinator.Report report, String id) {
        return report.tasks().stream().filter(task -> task.getId().equals(id)).findFirst().orElseThrow();
    }

    private static Scenario.Host host(String id, int risk, int criticality) {
        return new Scenario.Host(id, id, risk, criticality);
    }

    private static Scenario.Edge edge(String id, String source, String target, double cost) {
        return new Scenario.Edge(id, source, target, cost, false);
    }

    private static Scenario.Task task(String id, String hostName, int effort, int value, int days) {
        return new Scenario.Task(id, hostName, effort, value, days);
    }

    private static Scenario scenario(List<Scenario.Host> hosts, List<Scenario.Edge> links, List<Scenario.Task> tasks) {
        return new Scenario("fixture", "Coordinator fixture", "soc", hosts, links,
                List.of(new Scenario.Team("team-3", 3), new Scenario.Team("team-2", 2), new Scenario.Team("team-1", 1)), tasks);
    }
}
