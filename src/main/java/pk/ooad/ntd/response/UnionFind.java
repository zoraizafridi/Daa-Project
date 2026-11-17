package pk.ooad.ntd.response;

import java.util.Objects;

public class UnionFind {
    private final int[] parent;
    private final int[] rank;
    private final OperationCounter operations;

    public UnionFind(int size) {
        this(size, new OperationCounter());
    }

    public UnionFind(int size, OperationCounter operations) {
        this.operations = Objects.requireNonNull(operations, "operations must not be null");
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
        parent = new int[size];
        rank = new int[size];
        for (int i = 0; i < size; i++) parent[i] = i;
    }

    public int find(int x) {
        operations.incrementFindCalls();
        checkIndex(x);
        if (parent[x] != x) parent[x] = find(parent[x]);
        return parent[x];
    }

    public boolean union(int a, int b) {
        operations.incrementUnions();
        int rootA = find(a);
        int rootB = find(b);
        if (rootA == rootB) return false;
        if (rank[rootA] < rank[rootB]) {
            parent[rootA] = rootB;
        } else if (rank[rootA] > rank[rootB]) {
            parent[rootB] = rootA;
        } else {
            parent[rootB] = rootA;
            rank[rootA]++;
        }
        return true;
    }

    private void checkIndex(int x) {
        if (x < 0 || x >= parent.length) {
            throw new IndexOutOfBoundsException("index out of range: " + x);
        }
    }
}
