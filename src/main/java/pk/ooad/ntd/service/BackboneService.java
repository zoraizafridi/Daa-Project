package pk.ooad.ntd.service;

import pk.ooad.ntd.domain.BackboneResult;
import pk.ooad.ntd.response.AlgorithmTimer;
import pk.ooad.ntd.response.BackbonePlanner;
import pk.ooad.ntd.scenario.Scenario;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class BackboneService {
    public record RepairComparison(Scenario reducedScenario, List<String> compromisedLinkIds,
                                   AlgorithmTimer.Measurement<BackboneResult> original,
                                   AlgorithmTimer.Measurement<BackboneResult> incremental,
                                   AlgorithmTimer.Measurement<BackboneResult> fullRecompute,
                                   boolean sameEdges, boolean sameCost) {
        public RepairComparison {
            compromisedLinkIds = List.copyOf(compromisedLinkIds);
        }
    }

    public AlgorithmTimer.Measurement<BackboneResult> plan(Scenario scenario) {
        BackbonePlanner planner = new BackbonePlanner();
        BackbonePlanner.PreparedInput input = planner.prepare(scenario.toNetwork(), scenario.socHostId());
        return AlgorithmTimer.measure(() -> planner.plan(input));
    }

    public RepairComparison repair(Scenario scenario, List<String> linkIds) {
        BackbonePlanner planner = new BackbonePlanner();
        BackbonePlanner.PreparedInput input = planner.prepare(scenario.toNetwork(), scenario.socHostId());
        AlgorithmTimer.Measurement<BackboneResult> original = AlgorithmTimer.measure(() -> planner.plan(input));
        if (linkIds == null && original.result().getChosenLinks().size() < 3) {
            throw new IllegalArgumentException("Default repair requires a scenario backbone with at least 3 tree links");
        }
        List<String> broken = linkIds == null ? planner.defaultRepairLinkIds() : List.copyOf(linkIds);
        BackbonePlanner.PreparedRepair repair = planner.prepareRepair(broken);
        Set<String> brokenSet = Set.copyOf(broken);
        Scenario reduced = new Scenario(scenario.id(), scenario.name(), scenario.socHostId(), scenario.hosts(),
                scenario.links().stream().map(edge -> new Scenario.Edge(edge.id(), edge.sourceId(), edge.targetId(),
                        edge.cost(), edge.compromised() || brokenSet.contains(edge.id()))).toList(),
                scenario.teams(), scenario.tasks());
        BackbonePlanner fullPlanner = new BackbonePlanner();
        BackbonePlanner.PreparedInput fullInput = fullPlanner.prepare(reduced.toNetwork(), reduced.socHostId());
        AlgorithmTimer.Measurement<BackboneResult> incremental = AlgorithmTimer.measure(() -> planner.repair(repair));
        AlgorithmTimer.Measurement<BackboneResult> full = AlgorithmTimer.measure(() -> fullPlanner.plan(fullInput));
        return new RepairComparison(reduced, broken, original, incremental, full,
                edgeIds(incremental.result()).equals(edgeIds(full.result())),
                Double.compare(incremental.result().getTotalWeight(), full.result().getTotalWeight()) == 0);
    }

    private static Set<String> edgeIds(BackboneResult result) {
        return result.getChosenLinks().stream().map(link -> link.getId()).collect(Collectors.toSet());
    }
}
