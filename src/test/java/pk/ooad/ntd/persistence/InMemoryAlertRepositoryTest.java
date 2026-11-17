package pk.ooad.ntd.persistence;

import org.junit.jupiter.api.Test;
import pk.ooad.ntd.domain.AttackType;
import pk.ooad.ntd.domain.LowAlert;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryAlertRepositoryTest {
    @Test
    void boundedRepositoryKeepsNewestInsertionsAndReturnsTimestampOrderedSnapshots() {
        AlertRepository repository = new InMemoryAlertRepository(2);
        AlertRecord first = row("first", 1);
        AlertRecord later = row("later", 3);
        AlertRecord middle = row("middle", 2);
        repository.insertBatch(List.of(first, later, middle));
        assertEquals(2, repository.count());
        assertEquals(List.of(later, middle), repository.recent(500));
        assertEquals(List.of(later), repository.recent(1));
        assertTrue(repository.recent(0).isEmpty());
        assertTrue(repository.recent(-1).isEmpty());
        List<AlertRecord> snapshot = repository.recent(2);
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        repository.clear();
        assertEquals(0, repository.count());
        assertTrue(repository.recent(2).isEmpty());
        assertEquals(List.of(later, middle), snapshot);
    }

    @Test
    void emptyAndNullBatchesDoNotAddRowsAndCapacityIsAtLeastOne() {
        AlertRepository repository = new InMemoryAlertRepository(0);
        repository.insertBatch(null);
        repository.insertBatch(List.of());
        repository.insertBatch(Arrays.asList(null, row("one", 1), null, row("two", 2)));
        assertEquals(1, repository.count());
        assertEquals("two", repository.recent(1).get(0).id());
    }

    @Test
    void recordPreservesPlainDetectionFieldsAndSimulationFlag() {
        LowAlert alert = new LowAlert("id", Instant.EPOCH, AttackType.PORT_SCAN,
                "source", "target", "detector", "description");
        alert.markSimulated(true);
        assertEquals(new AlertRecord("id", Instant.EPOCH, "LOW", "PORT_SCAN", "source", "target",
                "detector", "description", true), AlertRecord.from(alert));
    }

    private AlertRecord row(String id, long seconds) {
        return new AlertRecord(id, Instant.ofEpochSecond(seconds), "LOW", "PORT_SCAN",
                "source", "target", "detector", "description", true);
    }
}
