package pk.ooad.ntd.service;

import org.junit.jupiter.api.Test;
import pk.ooad.ntd.scenario.Scenario;
import pk.ooad.ntd.scenario.ScenarioLoader;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssignmentServiceTest {
    @Test
    void comparesTheSameOnceGrownTasksAndMeasuresOnlySolverCalls() {
        Scenario scenario = ScenarioLoader.load("main-12-hosts-22-links");
        var compared = new AssignmentService().compare(scenario);
        assertEquals(290, compared.dp().result().getTotalValue());
        assertEquals(compared.dp().result().getTotalValue(), compared.bruteForce().result().getTotalValue());
        assertTrue(compared.dp().result().getTotalValue() >= compared.greedy().result().getTotalValue());
        assertEquals(120, compared.tasks().stream().filter(task -> task.getId().equals("task-host-10"))
                .findFirst().orElseThrow().getValue());
        assertEquals(100, scenario.tasks().get(3).baseValue());
        assertEquals(1, compared.dp().warmupRuns());
        assertEquals(5, compared.dp().measuredRuns());
        assertNull(compared.bruteForceError());
        assertEquals(290, new AssignmentService().assign(scenario).assignment().result().getTotalValue());
    }

    @Test
    void reportsBruteForceLimitWithoutSkippingDpOrGreedy() {
        Scenario base = ScenarioLoader.load("over-demand");
        Scenario many = new Scenario("many", "Many tasks", base.socHostId(), base.hosts(), base.links(),
                base.teams(), IntStream.range(0, 13).mapToObj(i ->
                new Scenario.Task("task-" + i, "Host A", 1, i + 1, 0)).toList());
        var compared = new AssignmentService().compare(many);
        assertNull(compared.bruteForce());
        assertNotNull(compared.bruteForceError());
        assertTrue(compared.bruteForceError().contains("12"));
        assertTrue(compared.dp().result().getTotalValue() > 0);
        assertTrue(compared.dp().result().getTotalValue() >= compared.greedy().result().getTotalValue());
    }
}
