package pk.ooad.ntd.persistence;

public final class InMemoryAlertRepository extends InMemoryRecordStore<AlertRecord> implements AlertRepository {
    public InMemoryAlertRepository(int maxRows) {
        super(maxRows, row -> row.timestamp() == null ? 0L : row.timestamp().toEpochMilli());
    }
}
