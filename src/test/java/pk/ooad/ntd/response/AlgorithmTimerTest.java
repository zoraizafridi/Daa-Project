package pk.ooad.ntd.response;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlgorithmTimerTest {
    @Test
    void performsOneWarmupAndFiveMeasuredRunsReturningAMeasuredResult() {
        AtomicInteger calls = new AtomicInteger();
        AlgorithmTimer.Measurement<Integer> measured = AlgorithmTimer.measure(calls::incrementAndGet);
        assertEquals(6, calls.get());
        assertEquals(1, measured.warmupRuns());
        assertEquals(5, measured.measuredRuns());
        assertTrue(measured.result() >= 2 && measured.result() <= 6);
        assertTrue(measured.medianNanos() >= 0);
    }
}
