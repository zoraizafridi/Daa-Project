package pk.ooad.ntd.response;

import pk.ooad.ntd.domain.AssetType;
import pk.ooad.ntd.domain.BackboneResult;
import pk.ooad.ntd.domain.Link;
import pk.ooad.ntd.domain.LinkStatus;
import pk.ooad.ntd.domain.Network;
import pk.ooad.ntd.domain.NetworkAsset;
import pk.ooad.ntd.util.ConfigManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class BackbonePlanner {
    private static final Comparator<Candidate> EDGE_ORDER = Comparator.comparingDouble(Candidate::cost)
            .thenComparing(Comparator.comparingDouble(Candidate::risk).reversed())
            .thenComparing(candidate -> candidate.link().getId());
    private Baseline latestFull;

    public BackboneResult plan(Network network) {
        Objects.requireNonNull(network, "network must not be null");
        return plan(network, getSocHost(network).getId());
    }

    public BackboneResult plan(Network network, String socHostId) {
        return plan(prepare(network, socHostId));
    }

    public PreparedInput prepare(Network network, String socHostId) {
        Objects.requireNonNull(network, "network must not be null");
        List<NetworkAsset> hosts = new ArrayList<>(network.getAssets());
        if (hosts.isEmpty()) throw new IllegalArgumentException("Network must contain at least one host");
        NetworkAsset socHost = network.getAsset(socHostId);
        if (socHost == null) throw new IllegalArgumentException("SOC host does not exist: " + socHostId);
        hosts.sort(Comparator.comparing(NetworkAsset::getName).thenComparing(NetworkAsset::getId));

        Map<NetworkAsset, Integer> indexes = new HashMap<>();
        for (int i = 0; i < hosts.size(); i++) indexes.put(hosts.get(i), i);
        List<Candidate> candidates = new ArrayList<>();
        for (Link link : network.getLinks()) {
            if (link.getStatus() == LinkStatus.COMPROMISED) continue;
            Integer source = indexes.get(link.getSource());
            Integer target = indexes.get(link.getTarget());
            if (source == null || target == null) {
                throw new IllegalArgumentException("Eligible link endpoints must exist in the network: " + link.getId());
            }
            candidates.add(new Candidate(link, source, target, link.getBaseCost(),
                    link.getSource().getRiskScore() + link.getTarget().getRiskScore()));
        }
        return new PreparedInput(hosts, indexes.get(socHost), candidates);
    }

    public BackboneResult plan(PreparedInput input) {
        Objects.requireNonNull(input, "input must not be null");
        OperationCounter operations = new OperationCounter();
        List<Candidate> sorted = new ArrayList<>(input.candidates);
        // Kruskal is O(m log m) in general, or O(m log n) for a simple graph.
        sorted.sort((left, right) -> {
            operations.incrementComparisons();
            return EDGE_ORDER.compare(left, right);
        });
        UnionFind sets = new UnionFind(input.hosts.size(), operations);
        List<Candidate> tree = new ArrayList<>();
        List<Candidate> nonTree = new ArrayList<>();
        for (Candidate candidate : sorted) {
            operations.incrementEdgesScanned();
            if (sets.union(candidate.source(), candidate.target())) {
                tree.add(candidate);
            } else {
                nonTree.add(candidate);
            }
        }
        BackboneResult result = result(input, sets, tree, links(sorted), operations);
        latestFull = new Baseline(input, List.copyOf(sorted), List.copyOf(tree), List.copyOf(nonTree));
        return result;
    }

    public List<String> defaultRepairLinkIds() {
        Baseline baseline = requireBaseline();
        if (baseline.tree().size() < 3) {
            throw new IllegalStateException("Latest full backbone tree must contain at least three links");
        }
        return baseline.tree().stream()
                .sorted(Comparator.comparingDouble(Candidate::cost).reversed()
                        .thenComparing(candidate -> candidate.link().getId()))
                .limit(3).map(candidate -> candidate.link().getId()).toList();
    }

    public PreparedRepair prepareRepair(List<String> brokenLinkIds) {
        Baseline baseline = requireBaseline();
        if (brokenLinkIds == null || brokenLinkIds.size() != 3) {
            throw new IllegalArgumentException("Exactly three broken link IDs are required");
        }
        Set<String> broken = new HashSet<>();
        for (String id : brokenLinkIds) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Broken link IDs must not be null or blank");
            }
            if (!broken.add(id)) {
                throw new IllegalArgumentException("Broken link IDs must be unique: " + id);
            }
        }
        Set<String> eligibleIds = new HashSet<>();
        Set<String> treeIds = new HashSet<>();
        for (Candidate candidate : baseline.sorted()) eligibleIds.add(candidate.link().getId());
        for (Candidate candidate : baseline.tree()) treeIds.add(candidate.link().getId());
        for (String id : brokenLinkIds) {
            if (!eligibleIds.contains(id)) {
                throw new IllegalArgumentException("Broken link is unknown or was not originally eligible: " + id);
            }
            if (!treeIds.contains(id)) {
                throw new IllegalArgumentException("Broken link must belong to the latest full backbone tree: " + id);
            }
        }

        List<Candidate> survivors = baseline.tree().stream()
                .filter(candidate -> !broken.contains(candidate.link().getId())).toList();
        List<Candidate> reducedCandidates = baseline.input().candidates.stream()
                .filter(candidate -> !broken.contains(candidate.link().getId())).toList();
        PreparedInput reduced = new PreparedInput(baseline.input().hosts, baseline.input().socIndex,
                reducedCandidates);
        List<Link> sortedCandidates = baseline.sorted().stream()
                .filter(candidate -> !broken.contains(candidate.link().getId())).map(Candidate::link).toList();
        Map<Candidate, Integer> positions = new HashMap<>();
        for (int i = 0; i < baseline.sorted().size(); i++) positions.put(baseline.sorted().get(i), i);
        return new PreparedRepair(brokenLinkIds, reduced, survivors, baseline.nonTree(), sortedCandidates, positions);
    }

    public BackboneResult repair(PreparedRepair input) {
        Objects.requireNonNull(input, "repair input must not be null");
        OperationCounter operations = new OperationCounter();
        UnionFind sets = new UnionFind(input.reducedInput.hosts.size(), operations);
        for (Candidate survivor : input.survivors) sets.union(survivor.source(), survivor.target());
        List<Candidate> replacements = new ArrayList<>();
        for (Candidate candidate : input.nonTree) {
            operations.incrementEdgesScanned();
            if (sets.union(candidate.source(), candidate.target())) replacements.add(candidate);
        }

        // Merge chosen subsequences by cached position to preserve exact floating-point summation order.
        List<Candidate> chosen = new ArrayList<>(input.survivors.size() + replacements.size());
        int survivor = 0;
        int replacement = 0;
        while (survivor < input.survivors.size() && replacement < replacements.size()) {
            Candidate left = input.survivors.get(survivor);
            Candidate right = replacements.get(replacement);
            if (input.positions.get(left) < input.positions.get(right)) {
                chosen.add(left);
                survivor++;
            } else {
                chosen.add(right);
                replacement++;
            }
        }
        chosen.addAll(input.survivors.subList(survivor, input.survivors.size()));
        chosen.addAll(replacements.subList(replacement, replacements.size()));
        return result(input.reducedInput, sets, chosen, input.sortedCandidates, operations);
    }

    public PreparedInput reducedInput(PreparedRepair input) {
        return Objects.requireNonNull(input, "repair input must not be null").reducedInput;
    }

    private BackboneResult result(PreparedInput input, UnionFind sets, List<Candidate> chosen,
                                  List<Link> sortedCandidates, OperationCounter operations) {
        double totalWeight = 0.0;
        for (Candidate candidate : chosen) totalWeight += candidate.cost();
        if (!Double.isFinite(totalWeight)) {
            throw new IllegalArgumentException("Backbone total cost exceeds the finite supported range");
        }
        List<NetworkAsset> unreachable = new ArrayList<>();
        int socRoot = sets.find(input.socIndex);
        for (int i = 0; i < input.hosts.size(); i++) {
            if (sets.find(i) != socRoot) unreachable.add(input.hosts.get(i));
        }
        return new BackboneResult(links(chosen), totalWeight, unreachable.isEmpty(), unreachable,
                operations.snapshot(), sortedCandidates);
    }

    private static List<Link> links(List<Candidate> candidates) {
        return candidates.stream().map(Candidate::link).toList();
    }

    private Baseline requireBaseline() {
        if (latestFull == null) throw new IllegalStateException("A successful full backbone plan is required before repair");
        return latestFull;
    }

    private NetworkAsset getSocHost(Network network) {
        if (network.getAssets().isEmpty()) throw new IllegalArgumentException("Network must contain at least one host");
        String configuredId = ConfigManager.getInstance().getString("soc.host.id", null);
        if (configuredId != null && !configuredId.isBlank()) {
            NetworkAsset soc = network.getAsset(configuredId.trim());
            if (soc == null) throw new IllegalArgumentException("Configured SOC host does not exist: " + configuredId);
            return soc;
        }
        return network.getAssets().stream()
                .filter(asset -> asset.getType() == AssetType.ROUTER)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("A SOC host must be declared for this network"));
    }

    public static final class PreparedInput {
        private final List<NetworkAsset> hosts;
        private final int socIndex;
        private final List<Candidate> candidates;

        private PreparedInput(List<NetworkAsset> hosts, int socIndex, List<Candidate> candidates) {
            this.hosts = List.copyOf(hosts);
            this.socIndex = socIndex;
            this.candidates = List.copyOf(candidates);
        }
    }

    public static final class PreparedRepair {
        private final List<String> brokenLinkIds;
        private final PreparedInput reducedInput;
        private final List<Candidate> survivors;
        private final List<Candidate> nonTree;
        private final List<Link> sortedCandidates;
        private final Map<Candidate, Integer> positions;

        private PreparedRepair(List<String> brokenLinkIds, PreparedInput reducedInput,
                               List<Candidate> survivors, List<Candidate> nonTree,
                               List<Link> sortedCandidates, Map<Candidate, Integer> positions) {
            this.brokenLinkIds = List.copyOf(brokenLinkIds);
            this.reducedInput = reducedInput;
            this.survivors = List.copyOf(survivors);
            this.nonTree = List.copyOf(nonTree);
            this.sortedCandidates = List.copyOf(sortedCandidates);
            this.positions = Map.copyOf(positions);
        }

        public List<String> getBrokenLinkIds() {
            return brokenLinkIds;
        }
    }

    private record Candidate(Link link, int source, int target, double cost, double risk) {
    }

    private record Baseline(PreparedInput input, List<Candidate> sorted, List<Candidate> tree,
                            List<Candidate> nonTree) {
    }
}
