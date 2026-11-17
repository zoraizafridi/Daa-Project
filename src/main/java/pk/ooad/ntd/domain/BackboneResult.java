package pk.ooad.ntd.domain;

import pk.ooad.ntd.response.OperationCounter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class BackboneResult {
    private final List<Link> chosenLinks;
    private final double totalWeight;
    private final boolean connected;
    private final List<NetworkAsset> unreachable;
    private final OperationCounter.Snapshot operations;
    private final List<Link> sortedCandidates;

    public BackboneResult(List<Link> chosenLinks, double totalWeight, boolean connected, List<NetworkAsset> unreachable) {
        this(chosenLinks, totalWeight, connected, unreachable,
                new OperationCounter.Snapshot(0, 0, 0, 0), List.of());
    }

    public BackboneResult(List<Link> chosenLinks, double totalWeight, boolean connected,
                          List<NetworkAsset> unreachable, OperationCounter.Snapshot operations,
                          List<Link> sortedCandidates) {
        this.chosenLinks = chosenLinks != null ? new ArrayList<>(chosenLinks) : new ArrayList<>();
        this.totalWeight = totalWeight;
        this.connected = connected;
        this.unreachable = unreachable != null ? new ArrayList<>(unreachable) : new ArrayList<>();
        this.operations = Objects.requireNonNull(operations, "operations must not be null");
        this.sortedCandidates = List.copyOf(sortedCandidates);
    }

    public List<Link> getChosenLinks() {
        return Collections.unmodifiableList(chosenLinks);
    }

    public double getTotalWeight() {
        return totalWeight;
    }

    public boolean isConnected() {
        return connected;
    }

    public List<NetworkAsset> getUnreachable() {
        return Collections.unmodifiableList(unreachable);
    }

    public OperationCounter.Snapshot getOperations() {
        return operations;
    }

    public List<Link> getSortedCandidates() {
        return sortedCandidates;
    }

    @Override
    public String toString() {
        return "BackboneResult{" +
                "chosenLinksCount=" + chosenLinks.size() +
                ", totalWeight=" + totalWeight +
                ", connected=" + connected +
                ", unreachableCount=" + unreachable.size() +
                '}';
    }
}
