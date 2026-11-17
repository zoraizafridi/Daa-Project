package pk.ooad.ntd.response;

import org.junit.jupiter.api.Test;
import pk.ooad.ntd.domain.BackboneResult;
import pk.ooad.ntd.domain.Link;
import pk.ooad.ntd.domain.LinkStatus;
import pk.ooad.ntd.domain.Network;
import pk.ooad.ntd.domain.NetworkAsset;
import pk.ooad.ntd.domain.Router;
import pk.ooad.ntd.domain.Server;
import pk.ooad.ntd.scenario.Scenario;
import pk.ooad.ntd.scenario.ScenarioLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackboneRepairTest {
    @Test
    void mainDefaultFailuresAreExactlyHighestThreeAndMatchScenarioCopyWithoutMutation() {
        Scenario scenario = ScenarioLoader.load("main-12-hosts-22-links");
        Network original = scenario.toNetwork();
        List<LinkStatus> statuses = statuses(original);
        List<Double> risks = risks(original);
        BackbonePlanner planner = new BackbonePlanner();
        BackboneResult baseline = planner.plan(planner.prepare(original, scenario.socHostId()));
        assertEquals(66.0, baseline.getTotalWeight());
        assertEquals(chainIds(11), ids(baseline.getChosenLinks()));
        assertEquals(22, baseline.getOperations().edgesScanned());
        assertEquals(22, baseline.getOperations().unions());
        assertTrue(baseline.getOperations().comparisons() > 0);
        assertEquals(List.of("chain-11", "chain-10", "chain-09"), planner.defaultRepairLinkIds());
        assertThrows(UnsupportedOperationException.class, () -> planner.defaultRepairLinkIds().add("other"));

        List<String> broken = planner.defaultRepairLinkIds();
        BackbonePlanner.PreparedRepair prepared = planner.prepareRepair(broken);
        BackboneResult repaired = planner.repair(prepared);
        BackboneResult full = planner.plan(planner.reducedInput(prepared));
        assertEquivalent(full, repaired, "main default");
        List<String> expected = new ArrayList<>(chainIds(8));
        expected.addAll(List.of("extra-09", "extra-10", "extra-11"));
        assertEquals(expected, ids(repaired.getChosenLinks()));
        assertEquals(123.0, repaired.getTotalWeight());
        assertTrue(repaired.isConnected());
        assertEquals(0, repaired.getOperations().comparisons());
        assertEquals(11, repaired.getOperations().edgesScanned());
        assertEquals(19, full.getOperations().edgesScanned());
        assertEquals(19, repaired.getOperations().unions());
        assertEquals(19, full.getOperations().unions());
        assertTrue(repaired.getOperations().edgesScanned() < full.getOperations().edgesScanned());
        assertTrue(full.getOperations().comparisons() > 0);
        assertAbsent(broken, repaired);
        assertEquals(statuses, statuses(original));
        assertEquals(risks, risks(original));
        assertEquals(12, original.getAssets().size());
        assertEquals(22, original.getLinks().size());
        assertEquals(scenario, ScenarioLoader.load(scenario.id()));

        Scenario copy = new Scenario(scenario.id(), scenario.name(), scenario.socHostId(), scenario.hosts(),
                scenario.links().stream().map(edge -> new Scenario.Edge(edge.id(), edge.sourceId(), edge.targetId(),
                        edge.cost(), edge.compromised() || broken.contains(edge.id()))).toList(),
                scenario.teams(), scenario.tasks());
        BackboneResult copyFull = new BackbonePlanner().plan(copy.toNetwork(), copy.socHostId());
        assertEquivalent(copyFull, repaired, "newly compromised scenario copy");
        assertEquals(3, copy.links().stream().filter(Scenario.Edge::compromised).count());
        assertTrue(original.getCompromisedLinks().isEmpty());
    }

    @Test
    void mainExplicitFailuresAreCopiedAndPreparedRepairSurvivesCacheReplacement() {
        Scenario scenario = ScenarioLoader.load("main-12-hosts-22-links");
        Network network = scenario.toNetwork();
        BackbonePlanner planner = new BackbonePlanner();
        planner.plan(network, scenario.socHostId());
        List<String> requested = new ArrayList<>(List.of("chain-02", "chain-05", "chain-08"));
        BackbonePlanner.PreparedRepair prepared = planner.prepareRepair(requested);
        requested.set(0, "unknown");
        assertEquals(List.of("chain-02", "chain-05", "chain-08"), prepared.getBrokenLinkIds());
        assertThrows(UnsupportedOperationException.class, () -> prepared.getBrokenLinkIds().clear());
        BackboneResult first = planner.repair(prepared);
        List<String> expected = new ArrayList<>(chainIds(11));
        expected.removeAll(prepared.getBrokenLinkIds());
        expected.addAll(List.of("extra-01", "extra-05", "extra-08"));
        assertEquals(expected, ids(first.getChosenLinks()));
        assertEquals(122.0, first.getTotalWeight());
        assertAbsent(prepared.getBrokenLinkIds(), first);

        BackbonePlanner.PreparedInput reduced = planner.reducedInput(prepared);
        BackboneResult full = planner.plan(reduced);
        assertEquivalent(full, first, "explicit recomputation on same planner");
        BackboneResult second = planner.repair(prepared);
        assertEquivalent(first, second, "repeat after full recomputation overwrites cache");
        assertEquals(first.getOperations(), second.getOperations());
        assertEquals(full.getOperations(), planner.plan(reduced).getOperations());
        planner.plan(hosts(1), "h0");
        assertThrows(IllegalStateException.class, planner::defaultRepairLinkIds);
        BackboneResult afterUnrelatedPlan = planner.repair(prepared);
        assertEquivalent(first, afterUnrelatedPlan, "repeat after unrelated full plan");
        assertEquals(first.getOperations(), afterUnrelatedPlan.getOperations());
        assertEquivalent(first, new BackbonePlanner().repair(prepared), "self-contained prepared repair");
        assertTrue(network.getCompromisedLinks().isEmpty());
    }

    @Test
    void rejectsMissingBaselineSmallTreesWrongCountsDuplicatesNonTreeUnknownAndIneligibleIds() {
        BackbonePlanner planner = new BackbonePlanner();
        assertThrows(IllegalStateException.class, planner::defaultRepairLinkIds);
        assertThrows(IllegalStateException.class, () -> planner.prepareRepair(List.of("a", "b", "c")));
        assertThrows(IllegalStateException.class, () -> planner.prepareRepair(null));
        planner.prepare(hosts(4), "h0");
        assertThrows(IllegalStateException.class, planner::defaultRepairLinkIds);
        planner.plan(hosts(1), "h0");
        assertThrows(IllegalStateException.class, planner::defaultRepairLinkIds);
        planner.plan(ScenarioLoader.load("disconnected").toNetwork(), "soc");
        assertThrows(IllegalStateException.class, planner::defaultRepairLinkIds);
        assertThrows(IllegalArgumentException.class,
                () -> planner.prepareRepair(List.of("soc-reachable", "remote-pair", "unknown")));

        Network network = hosts(4);
        add(network, "a", 0, 1, 1);
        add(network, "b", 1, 2, 2);
        add(network, "c", 2, 3, 3);
        add(network, "non-tree", 0, 3, 10);
        add(network, "compromised", 0, 2, 0.5).markCompromised();
        planner.plan(network, "h0");
        assertThrows(IllegalArgumentException.class, () -> planner.prepareRepair(null));
        for (List<String> wrong : List.of(List.<String>of(), List.of("a"), List.of("a", "b"),
                List.of("a", "b", "c", "non-tree"))) {
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> planner.prepareRepair(wrong)).getMessage().contains("Exactly three"));
        }
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> planner.prepareRepair(List.of("a", "a", "b"))).getMessage().contains("unique"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> planner.prepareRepair(List.of("a", "b", "non-tree"))).getMessage().contains("tree"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> planner.prepareRepair(List.of("a", "b", "unknown"))).getMessage().contains("unknown"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> planner.prepareRepair(List.of("a", "b", "compromised"))).getMessage().contains("eligible"));
        assertThrows(IllegalArgumentException.class, () -> planner.prepareRepair(List.of("a", "b", " ")));
        List<String> withNull = new ArrayList<>(List.of("a", "b"));
        withNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> planner.prepareRepair(withNull));
        assertEquals(List.of("c", "b", "a"), planner.defaultRepairLinkIds());
        assertThrows(IllegalArgumentException.class, () -> planner.plan(new Network(), "h0"));
        assertEquals("SOC host does not exist: missing", assertThrows(IllegalArgumentException.class,
                () -> planner.prepare(network, "missing")).getMessage());
        assertEquals(List.of("c", "b", "a"), planner.defaultRepairLinkIds());
        assertTrue(planner.repair(planner.prepareRepair(List.of("a", "b", "c"))).getChosenLinks().stream()
                .noneMatch(link -> link.getId().equals("compromised")));
    }

    @Test
    void disconnectedRepairPreservesRemoteForestAndExplicitSocUnreachableOrdering() {
        Network network = hosts(8);
        add(network, "a", 0, 1, 0.1);
        add(network, "b", 1, 2, 0.2);
        add(network, "c", 2, 3, 0.3);
        add(network, "d", 4, 5, 0.4);
        add(network, "e", 5, 6, 0.5);
        add(network, "remote-replacement", 4, 6, 0.6);
        add(network, "compromised-bridge", 3, 4, 0.05).markCompromised();
        List<LinkStatus> originalStatuses = statuses(network);
        for (String soc : List.of("h0", "h4")) {
            BackbonePlanner planner = new BackbonePlanner();
            BackboneResult baseline = planner.plan(network, soc);
            assertEquals(5, baseline.getChosenLinks().size());
            assertFalse(baseline.isConnected());
            assertEquals(List.of("e", "d", "c"), planner.defaultRepairLinkIds());
            BackbonePlanner.PreparedRepair prepared = planner.prepareRepair(planner.defaultRepairLinkIds());
            BackboneResult repaired = planner.repair(prepared);
            BackboneResult full = planner.plan(planner.reducedInput(prepared));
            assertEquivalent(full, repaired, "disconnected SOC " + soc);
            assertEquals(List.of("a", "b", "remote-replacement"), ids(repaired.getChosenLinks()));
            assertFalse(repaired.isConnected());
            assertEquals(soc.equals("h0") ? List.of("h3", "h4", "h5", "h6", "h7")
                    : List.of("h0", "h1", "h2", "h3", "h5", "h7"), assetIds(repaired.getUnreachable()));
            assertEquals(0, repaired.getOperations().comparisons());
            assertEquals(1, repaired.getOperations().edgesScanned());
            assertEquals(3, full.getOperations().edgesScanned());
            assertEquals(3, repaired.getOperations().unions());
            assertAbsent(prepared.getBrokenLinkIds(), repaired);
        }
        assertEquals(originalStatuses, statuses(network));
    }

    @Test
    void sparseTreeFailuresCanLeaveNoCandidatesAndAllCompromisedInputHasNoRepairBaseline() {
        Network sparse = hosts(4);
        add(sparse, "a", 0, 1, 0.1);
        add(sparse, "b", 1, 2, 0.2);
        add(sparse, "c", 2, 3, 0.3);
        add(sparse, "already-compromised", 0, 3, 0.01).markCompromised();
        BackbonePlanner planner = new BackbonePlanner();
        planner.plan(sparse, "h0");
        BackbonePlanner.PreparedRepair prepared = planner.prepareRepair(List.of("c", "a", "b"));
        BackboneResult repaired = planner.repair(prepared);
        BackboneResult full = planner.plan(planner.reducedInput(prepared));
        assertEquivalent(full, repaired, "no surviving eligible edges");
        assertEquals(List.of(), repaired.getChosenLinks());
        assertEquals(0.0, repaired.getTotalWeight());
        assertFalse(repaired.isConnected());
        assertEquals(List.of("h1", "h2", "h3"), assetIds(repaired.getUnreachable()));
        assertEquals(new OperationCounter.Snapshot(0, 5, 0, 0), repaired.getOperations());
        assertEquals(repaired.getOperations(), full.getOperations());
        assertEquals(List.of(), repaired.getSortedCandidates());
        assertEquals(1, sparse.getCompromisedLinks().size());
        assertEquivalent(repaired, planner.repair(prepared), "repeat after empty forest cache");

        sparse.getLinks().forEach(Link::markCompromised);
        BackboneResult allCompromised = planner.plan(sparse, "h0");
        assertEquivalent(full, allCompromised, "all originally compromised");
        assertEquals(new OperationCounter.Snapshot(0, 5, 0, 0), allCompromised.getOperations());
        assertThrows(IllegalStateException.class, planner::defaultRepairLinkIds);
        assertThrows(IllegalArgumentException.class, () -> planner.prepareRepair(List.of("a", "b", "c")));
    }

    @Test
    void parallelEdgesTiedRiskAndCostUseOriginalTotalOrderAndExcludeCompromisedEdges() {
        Network network = hosts(4);
        network.getAsset("h1").addRisk(9);
        add(network, "z-parallel", 0, 1, 0.1);
        add(network, "b-low-risk", 0, 2, 0.1);
        add(network, "a-parallel", 0, 1, 0.1);
        add(network, "c-high-risk", 1, 2, 0.1);
        add(network, "e-id", 0, 3, 0.2);
        add(network, "d-id", 2, 3, 0.2);
        add(network, "compromised", 0, 3, 0.01).markCompromised();
        BackbonePlanner planner = new BackbonePlanner();
        BackboneResult baseline = planner.plan(network, "h0");
        assertEquals(List.of("a-parallel", "c-high-risk", "z-parallel", "b-low-risk", "d-id", "e-id"),
                ids(baseline.getSortedCandidates()));
        assertEquals(List.of("a-parallel", "c-high-risk", "d-id"), ids(baseline.getChosenLinks()));
        assertEquals(List.of("d-id", "a-parallel", "c-high-risk"), planner.defaultRepairLinkIds());
        BackbonePlanner.PreparedRepair prepared = planner.prepareRepair(planner.defaultRepairLinkIds());
        BackboneResult repaired = planner.repair(prepared);
        BackboneResult full = planner.plan(planner.reducedInput(prepared));
        assertEquivalent(full, repaired, "parallel and tied edges");
        assertEquals(List.of("z-parallel", "b-low-risk", "e-id"), ids(repaired.getChosenLinks()));
        assertEquals(0.4, repaired.getTotalWeight());
        assertEquals(0, repaired.getOperations().comparisons());
        assertEquals(3, repaired.getOperations().edgesScanned());
        assertAbsent(prepared.getBrokenLinkIds(), repaired);
        assertEquals(1, network.getCompromisedLinks().size());

        Network tied = hosts(5);
        add(tied, "d", 0, 1, 1);
        add(tied, "b", 1, 2, 1);
        add(tied, "c", 2, 3, 1);
        add(tied, "a", 3, 4, 1);
        tied.getAsset("h1").addRisk(50);
        planner.plan(tied, "h0");
        assertEquals(List.of("a", "b", "c"), planner.defaultRepairLinkIds());
    }

    @Test
    void fractionalSurvivorsAndReplacementsAreEmittedInCachedOrderForExactSummation() {
        Network network = hosts(6);
        add(network, "chain-a", 0, 1, 0.1);
        add(network, "chain-b", 1, 2, 0.2);
        add(network, "chain-c", 2, 3, 0.3);
        add(network, "chain-d", 3, 4, 0.4);
        add(network, "chain-e", 4, 5, 0.5);
        add(network, "replacement-a", 0, 1, 0.11);
        add(network, "replacement-c", 2, 3, 0.31);
        add(network, "replacement-e", 4, 5, 0.51);
        BackbonePlanner planner = new BackbonePlanner();
        planner.plan(network, "h0");
        BackbonePlanner.PreparedRepair prepared = planner.prepareRepair(List.of("chain-a", "chain-c", "chain-e"));
        BackboneResult repaired = planner.repair(prepared);
        BackboneResult full = planner.plan(planner.reducedInput(prepared));
        assertEquivalent(full, repaired, "fractional interleaving");
        assertEquals(List.of("replacement-a", "chain-b", "replacement-c", "chain-d", "replacement-e"),
                ids(repaired.getChosenLinks()));
        assertEquals(3, repaired.getOperations().edgesScanned());
        assertEquals(5, full.getOperations().edgesScanned());
        assertEquals(0, repaired.getOperations().comparisons());
    }

    @Test
    void preparationFreezesMutableEndpointRiskOrderingAndRepeatedRunsDoNotMutateInput() {
        Network network = hosts(5);
        network.getAsset("h1").addRisk(9);
        for (int source = 0; source < 5; source++) {
            for (int target = source + 1; target < 5; target++) {
                add(network, "edge-" + source + "-" + target, source, target, 0.1);
            }
        }
        BackbonePlanner planner = new BackbonePlanner();
        BackboneResult reference = planner.plan(network, "h0");
        BackbonePlanner.PreparedInput prepared = planner.prepare(network, "h0");
        network.getAsset("h1").resetRisk();
        network.getAsset("h4").addRisk(100);
        BackboneResult frozen = planner.plan(prepared);
        assertEquivalent(reference, frozen, "risk values captured before first prepared plan");
        BackbonePlanner.PreparedRepair repairInput = planner.prepareRepair(planner.defaultRepairLinkIds());
        BackboneResult repaired = planner.repair(repairInput);
        BackboneResult full = planner.plan(planner.reducedInput(repairInput));
        assertEquivalent(full, repaired, "reduced input inherits captured risk values");
        BackboneResult live = planner.plan(network, "h0");
        assertNotEquals(ids(reference.getSortedCandidates()), ids(live.getSortedCandidates()));
        assertEquivalent(frozen, planner.plan(prepared), "repeat frozen plan after live full plan");
        assertEquals(frozen.getOperations(), planner.plan(prepared).getOperations());
        assertEquivalent(repaired, planner.repair(repairInput), "repeat frozen repair after live full plan");
        assertEquals(repaired.getOperations(), planner.repair(repairInput).getOperations());
        assertEquals(100.0, network.getAsset("h4").getRiskScore());
        assertEquals(0.0, network.getAsset("h1").getRiskScore());
        assertTrue(network.getCompromisedLinks().isEmpty());
    }

    @Test
    void unionFindCountersIncludeEveryRecursiveCompressionAndEveryUnionAttemptExactly() {
        OperationCounter operations = new OperationCounter();
        UnionFind sets = new UnionFind(8, operations);
        assertTrue(sets.union(0, 1));
        assertTrue(sets.union(2, 3));
        assertTrue(sets.union(4, 5));
        assertTrue(sets.union(6, 7));
        assertTrue(sets.union(0, 2));
        assertTrue(sets.union(4, 6));
        assertTrue(sets.union(0, 4));
        assertEquals(new OperationCounter.Snapshot(0, 14, 7, 0), operations.snapshot());
        assertEquals(0, sets.find(7));
        OperationCounter.Snapshot compressed = operations.snapshot();
        assertEquals(new OperationCounter.Snapshot(0, 18, 7, 0), compressed);
        assertEquals(0, sets.find(7));
        assertEquals(new OperationCounter.Snapshot(0, 20, 7, 0), operations.snapshot());
        assertFalse(sets.union(3, 5));
        assertEquals(new OperationCounter.Snapshot(0, 26, 8, 0), operations.snapshot());
        assertEquals(0, sets.find(3));
        assertEquals(0, sets.find(5));
        assertEquals(new OperationCounter.Snapshot(0, 30, 8, 0), operations.snapshot());
        assertThrows(IndexOutOfBoundsException.class, () -> sets.union(8, 0));
        assertEquals(new OperationCounter.Snapshot(0, 31, 9, 0), operations.snapshot());
        assertEquals(new OperationCounter.Snapshot(0, 18, 7, 0), compressed);
        assertThrows(IllegalArgumentException.class, () -> new UnionFind(-1, operations));
        assertThrows(NullPointerException.class, () -> new UnionFind(1, null));
        UnionFind legacy = new UnionFind(2);
        assertTrue(legacy.union(0, 1));
        assertFalse(legacy.union(1, 0));
        assertEquals(legacy.find(0), legacy.find(1));
    }

    @Test
    void fullAndRepairCountersIncludeSocChecksButNotSurvivorInitializationInEdgesScanned() {
        Network network = hosts(4);
        add(network, "a", 0, 1, 1);
        add(network, "b", 2, 3, 2);
        add(network, "c", 1, 2, 3);
        add(network, "d", 0, 3, 4);
        BackbonePlanner planner = new BackbonePlanner();
        BackboneResult full = planner.plan(network, "h0");
        assertEquals(new OperationCounter.Snapshot(3, 19, 4, 4), full.getOperations());
        BackbonePlanner.PreparedRepair prepared = planner.prepareRepair(List.of("a", "b", "c"));
        BackboneResult repaired = planner.repair(prepared);
        assertEquals(new OperationCounter.Snapshot(0, 8, 1, 1), repaired.getOperations());
        BackboneResult reducedFull = planner.plan(planner.reducedInput(prepared));
        assertEquivalent(reducedFull, repaired, "exact small counters");
        assertEquals(repaired.getOperations(), reducedFull.getOperations());

        Network withSurvivor = hosts(5);
        add(withSurvivor, "a", 0, 1, 1);
        add(withSurvivor, "b", 1, 2, 2);
        add(withSurvivor, "c", 2, 3, 3);
        add(withSurvivor, "d", 3, 4, 4);
        planner.plan(withSurvivor, "h0");
        BackbonePlanner.PreparedRepair survivorInput = planner.prepareRepair(List.of("b", "c", "d"));
        BackboneResult survivorRepair = planner.repair(survivorInput);
        assertEquals(new OperationCounter.Snapshot(0, 9, 1, 0), survivorRepair.getOperations());
        assertEquals(1, planner.plan(planner.reducedInput(survivorInput)).getOperations().edgesScanned());
    }

    @Test
    void resultListsAndSnapshotsAreImmutableDefensiveCopiesAndLegacyConstructorRemainsSupported() {
        Network network = hosts(2);
        Link link = add(network, "a", 0, 1, 0.1);
        List<Link> chosen = new ArrayList<>(List.of(link));
        List<Link> candidates = new ArrayList<>(List.of(link));
        List<NetworkAsset> unreachable = new ArrayList<>(List.of(network.getAsset("h1")));
        OperationCounter counter = new OperationCounter();
        counter.incrementComparisons();
        counter.incrementFindCalls();
        counter.incrementUnions();
        counter.incrementEdgesScanned();
        BackboneResult result = new BackboneResult(chosen, 0.1, false, unreachable, counter.snapshot(), candidates);
        BackboneResult legacy = new BackboneResult(chosen, 0.1, false, unreachable);
        chosen.clear();
        candidates.clear();
        unreachable.clear();
        counter.incrementComparisons();
        assertEquals(List.of(link), result.getChosenLinks());
        assertEquals(List.of(link), result.getSortedCandidates());
        assertEquals(List.of(network.getAsset("h1")), result.getUnreachable());
        assertEquals(new OperationCounter.Snapshot(1, 1, 1, 1), result.getOperations());
        assertEquals(new OperationCounter.Snapshot(0, 0, 0, 0), legacy.getOperations());
        assertEquals(List.of(), legacy.getSortedCandidates());
        assertEquals(List.of(link), legacy.getChosenLinks());
        assertThrows(UnsupportedOperationException.class, () -> result.getChosenLinks().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.getSortedCandidates().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.getUnreachable().clear());
        BackboneResult nullLegacy = new BackboneResult(null, 0, true, null);
        assertTrue(nullLegacy.getChosenLinks().isEmpty());
        assertTrue(nullLegacy.getUnreachable().isEmpty());
    }

    @Test
    void twoHundredDeterministicRandomGraphsMatchExactFullReducedForestAndDoubleCost() {
        Random random = new Random(0x3BA_CCB0EL);
        int becameDisconnected = 0;
        int initiallyDisconnected = 0;
        int strictlyFewerScans = 0;
        for (int trial = 0; trial < 200; trial++) {
            boolean splitComponents = trial % 5 == 1;
            int count = splitComponents ? 8 + random.nextInt(9) : 4 + random.nextInt(13);
            Network network = hosts(count);
            for (NetworkAsset host : network.getAssets()) {
                host.addRisk(trial % 3 == 0 ? 3 : 1 + random.nextInt(4));
            }
            int split = splitComponents ? count / 2 : count;
            for (int target = 1; target < count; target++) {
                if (target == split) continue;
                int first = target < split ? 0 : split;
                int source = first + random.nextInt(target - first);
                add(network, "tree-" + target, source, target, (1 + random.nextInt(5)) / 10.0);
            }
            boolean sparse = trial % 5 == 0;
            if (!sparse) {
                for (int source = 0; source < count; source++) {
                    for (int target = source + 1; target < count; target++) {
                        if ((source < split) != (target < split)) continue;
                        if (random.nextDouble() < (trial % 5 == 2 ? 0.75 : 0.25)) {
                            Link link = add(network, "extra-" + source + "-" + target, source, target,
                                    (1 + random.nextInt(5)) / 10.0);
                            if (random.nextInt(5) == 0) link.markCompromised();
                        }
                    }
                }
            }
            add(network, "existing-compromised", 0, count - 1, 0.05).markCompromised();
            List<LinkStatus> originalStatuses = statuses(network);
            List<Double> originalRisks = risks(network);
            BackbonePlanner planner = new BackbonePlanner();
            String soc = "h" + random.nextInt(count);
            BackboneResult baseline = planner.plan(planner.prepare(network, soc));
            String context = "random trial " + trial;
            assertTrue(baseline.getChosenLinks().size() >= 3, context);
            if (!baseline.isConnected()) initiallyDisconnected++;
            List<String> shuffled = new ArrayList<>(ids(baseline.getChosenLinks()));
            Collections.shuffle(shuffled, random);
            List<String> broken = List.copyOf(shuffled.subList(0, 3));
            assertEquals(3, new HashSet<>(broken).size(), context);
            BackbonePlanner.PreparedRepair prepared = planner.prepareRepair(broken);
            BackboneResult repaired = planner.repair(prepared);
            BackboneResult full = planner.plan(planner.reducedInput(prepared));
            assertEquivalent(full, repaired, context);
            assertAbsent(broken, repaired);
            assertEquals(0, repaired.getOperations().comparisons(), context);
            long eligible = network.getLinks().stream().filter(link -> link.getStatus() != LinkStatus.COMPROMISED).count();
            assertEquals(eligible - baseline.getChosenLinks().size(), repaired.getOperations().edgesScanned(), context);
            assertEquals(eligible - 3, full.getOperations().edgesScanned(), context);
            assertEquals(eligible - 3, repaired.getOperations().unions(), context);
            assertEquals(eligible - 3, full.getOperations().unions(), context);
            assertEquals((long) baseline.getChosenLinks().size() - 3,
                    full.getOperations().edgesScanned() - repaired.getOperations().edgesScanned(), context);
            if (baseline.getChosenLinks().size() > 3) {
                assertTrue(repaired.getOperations().edgesScanned() < full.getOperations().edgesScanned(), context);
                strictlyFewerScans++;
            }
            BackboneResult repeated = planner.repair(prepared);
            assertEquivalent(repaired, repeated, context + " repeated repair");
            assertEquals(repaired.getOperations(), repeated.getOperations(), context);
            assertEquals(originalStatuses, statuses(network), context);
            assertEquals(originalRisks, risks(network), context);
            assertTrue(network.getCompromisedLinks().size() >= 1, context);
            if (baseline.isConnected() && !repaired.isConnected()) becameDisconnected++;
            if (sparse) {
                assertFalse(repaired.isConnected(), context);
                assertEquals(0, repaired.getOperations().edgesScanned(), context);
            }
        }
        assertTrue(becameDisconnected >= 40, "all forty sparse connected trees must disconnect");
        assertEquals(40, initiallyDisconnected, "all forty two-component baselines must stay disconnected");
        assertTrue(strictlyFewerScans >= 40, "nontrivial survivors must reduce counted candidate visits");
    }

    private static void assertEquivalent(BackboneResult expected, BackboneResult actual, String context) {
        assertEquals(Set.copyOf(ids(expected.getChosenLinks())), Set.copyOf(ids(actual.getChosenLinks())),
                context + " chosen ID set");
        assertEquals(ids(expected.getChosenLinks()), ids(actual.getChosenLinks()), context + " cached chosen order");
        assertEquals(expected.getTotalWeight(), actual.getTotalWeight(), context + " exact double cost");
        assertEquals(Double.doubleToLongBits(expected.getTotalWeight()), Double.doubleToLongBits(actual.getTotalWeight()),
                context + " bit-exact double cost");
        assertEquals(expected.isConnected(), actual.isConnected(), context + " connectedness");
        assertEquals(assetIds(expected.getUnreachable()), assetIds(actual.getUnreachable()), context + " SOC unreachable");
        assertEquals(ids(expected.getSortedCandidates()), ids(actual.getSortedCandidates()), context + " sorted candidates");
    }

    private static void assertAbsent(List<String> broken, BackboneResult result) {
        assertTrue(Collections.disjoint(broken, ids(result.getChosenLinks())), "broken edges absent from chosen forest");
        assertTrue(Collections.disjoint(broken, ids(result.getSortedCandidates())), "broken edges absent from candidates");
    }

    private static Network hosts(int count) {
        Network network = new Network();
        for (int i = 0; i < count; i++) {
            String name = i < 10 ? "Host 0" + i : "Host " + i;
            NetworkAsset host = i == 0 ? new Router("h0", name, "10.0.0.1", 1)
                    : new Server("h" + i, name, "10.0.0." + (i + 1), 1);
            network.addAsset(host);
        }
        return network;
    }

    private static Link add(Network network, String id, int source, int target, double cost) {
        Link link = new Link(id, network.getAsset("h" + source), network.getAsset("h" + target), cost, 10);
        network.addLink(link);
        return link;
    }

    private static List<String> ids(List<Link> links) {
        return links.stream().map(Link::getId).toList();
    }

    private static List<String> assetIds(List<NetworkAsset> assets) {
        return assets.stream().map(NetworkAsset::getId).toList();
    }

    private static List<LinkStatus> statuses(Network network) {
        return network.getLinks().stream().map(Link::getStatus).toList();
    }

    private static List<Double> risks(Network network) {
        return network.getAssets().stream().map(NetworkAsset::getRiskScore).toList();
    }

    private static List<String> chainIds(int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= count; i++) ids.add("chain-" + (i < 10 ? "0" : "") + i);
        return ids;
    }
}
