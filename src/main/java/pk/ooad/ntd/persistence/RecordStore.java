package pk.ooad.ntd.persistence;

import java.util.List;

public interface RecordStore<T> {
    void insertBatch(List<T> batch);

    List<T> recent(int limit);

    int count();

    void clear();
}
