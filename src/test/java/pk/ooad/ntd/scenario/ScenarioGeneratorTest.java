package pk.ooad.ntd.scenario;

import org.junit.jupiter.api.Test;
import pk.ooad.ntd.response.BackbonePlanner;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioGeneratorTest {
    @Test
    void createsDeterministicConnectedScaleScenarioWithUniqueUndirectedPairs() {
        Scenario scenario = ScenarioGenerator.large(42L);
        assertEquals(scenario, ScenarioGenerator.large(42L));
        assertNotEquals(scenario, ScenarioGenerator.large(43L));
        assertEquals(200, scenario.hosts().size());
        assertEquals(1000, scenario.links().size());
        assertEquals(10, scenario.tasks().size());
        Set<String> pairs = new HashSet<>();
        for (Scenario.Edge edge : scenario.links()) {
            assertTrue(pairs.add(edge.sourceId() + ":" + edge.targetId()));
        }
        var result = new BackbonePlanner().plan(scenario.toNetwork(), scenario.socHostId());
        assertTrue(result.isConnected());
        assertEquals(199, result.getChosenLinks().size());
    }

    @Test
    void generatedScenarioIsDiscoverableAndFreshlyLoaded() {
        assertTrue(ScenarioLoader.availableIds().contains(ScenarioGenerator.LARGE_ID));
        assertEquals(ScenarioGenerator.large(42L), ScenarioLoader.load(ScenarioGenerator.LARGE_ID));
    }
}
