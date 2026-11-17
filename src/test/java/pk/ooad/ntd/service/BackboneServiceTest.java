package pk.ooad.ntd.service;

import org.junit.jupiter.api.Test;
import pk.ooad.ntd.scenario.Scenario;
import pk.ooad.ntd.scenario.ScenarioLoader;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackboneServiceTest {
    @Test
    void measuresDefaultRepairAndFullRecomputeOnIdenticalScenarioCopies() {
        Scenario scenario = ScenarioLoader.load("main-12-hosts-22-links");
        var report = new BackboneService().repair(scenario, null);
        assertEquals(List.of("chain-11", "chain-10", "chain-09"), report.compromisedLinkIds());
        assertEquals(66.0, report.original().result().getTotalWeight());
        assertEquals(123.0, report.incremental().result().getTotalWeight());
        assertTrue(report.sameEdges());
        assertTrue(report.sameCost());
        assertTrue(report.incremental().result().isConnected());
        assertEquals(1, report.incremental().warmupRuns());
        assertEquals(5, report.fullRecompute().measuredRuns());
        assertTrue(report.incremental().medianNanos() >= 0);
        assertTrue(report.fullRecompute().medianNanos() >= 0);
        assertFalse(scenario.links().stream().anyMatch(Scenario.Edge::compromised));
        assertEquals(3, report.reducedScenario().links().stream().filter(Scenario.Edge::compromised).count());
        assertEquals(0, report.incremental().result().getOperations().comparisons());
        assertTrue(report.incremental().result().getOperations().edgesScanned()
                < report.fullRecompute().result().getOperations().edgesScanned());
        assertEquals(scenario, ScenarioLoader.load(scenario.id()));
    }

    @Test
    void generatedScaleScenarioComparesCountersWithoutAssertingWallClockSpeed() {
        Scenario scenario = ScenarioLoader.load("large-200-hosts-1000-links");
        var report = new BackboneService().repair(scenario, null);
        assertTrue(report.sameEdges());
        assertTrue(report.sameCost());
        assertEquals(1000, report.original().result().getOperations().edgesScanned());
        assertEquals(997, report.fullRecompute().result().getOperations().edgesScanned());
        assertEquals(801, report.incremental().result().getOperations().edgesScanned());
        assertEquals(0, report.incremental().result().getOperations().comparisons());
        assertTrue(report.fullRecompute().result().getOperations().comparisons() > 0);
    }

    @Test
    void rejectsSelectedCostOverflowEvenWhenIndividualCostsAreFinite() {
        Scenario base = ScenarioLoader.load("main-12-hosts-22-links");
        Scenario huge = new Scenario(base.id(), base.name(), base.socHostId(), base.hosts(),
                base.links().stream().map(edge -> new Scenario.Edge(edge.id(), edge.sourceId(), edge.targetId(),
                        Double.MAX_VALUE, false)).toList(), base.teams(), base.tasks());
        assertThrows(IllegalArgumentException.class, () -> new BackboneService().plan(huge));
    }

    @Test
    void explicitFailuresAreValidatedAndNeverChangeTheOriginalScenario() {
        Scenario scenario = ScenarioLoader.load("main-12-hosts-22-links");
        BackboneService service = new BackboneService();
        var report = service.repair(scenario, List.of("chain-01", "chain-02", "chain-03"));
        assertTrue(report.sameEdges());
        assertTrue(report.sameCost());
        assertThrows(IllegalArgumentException.class, () -> service.repair(scenario, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> service.repair(scenario, List.of("extra-01", "chain-02", "chain-03")));
        assertFalse(scenario.links().stream().anyMatch(Scenario.Edge::compromised));
    }
}
