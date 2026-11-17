package pk.ooad.ntd.persistence;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.ToLongFunction;

class InMemoryRecordStore<T> implements RecordStore<T> {
    private final int maxRows;
    private final ToLongFunction<T> clock;
    private final Deque<T> rows = new ArrayDeque<>();

    InMemoryRecordStore(int maxRows, ToLongFunction<T> clock) {
        this.maxRows = Math.max(1, maxRows);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public synchronized void insertBatch(List<T> batch) {
        if (batch == null) {
            return;
        }
        for (T row : batch) {
            if (row == null) {
                continue;
            }
            if (rows.size() == maxRows) {
                rows.removeFirst();
            }
            rows.addLast(row);
        }
    }

    @Override
    public synchronized List<T> recent(int limit) {
        int count = Math.min(Math.max(limit, 0), rows.size());
        List<T> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingLong(clock).reversed());
        return List.copyOf(sorted.subList(0, count));
    }

    @Override
    public synchronized int count() {
        return rows.size();
    }

    @Override
    public synchronized void clear() {
        rows.clear();
    }
}
