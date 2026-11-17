package pk.ooad.ntd.response;

public final class OperationCounter {
    private long comparisons;
    private long findCalls;
    private long unions;
    private long edgesScanned;

    public void incrementComparisons() {
        comparisons++;
    }

    public void incrementFindCalls() {
        findCalls++;
    }

    public void incrementUnions() {
        unions++;
    }

    public void incrementEdgesScanned() {
        edgesScanned++;
    }

    public Snapshot snapshot() {
        return new Snapshot(comparisons, findCalls, unions, edgesScanned);
    }

    public record Snapshot(long comparisons, long findCalls, long unions, long edgesScanned) {
    }
}
