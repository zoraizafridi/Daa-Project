package pk.ooad.ntd.response;

import org.junit.jupiter.api.Test;
import pk.ooad.ntd.domain.MitigationTask;
import pk.ooad.ntd.domain.Server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgeGrowthTest {
    @Test
    void linearlyGrowsAndRoundsOnceWithoutMutatingTheBaseTask() {
        MitigationTask task = task(5, 3);
        MitigationTask grown = AgeGrowth.apply(task, 0.1);
        assertEquals(7, grown.getValue());
        assertEquals(5, task.getValue());
        assertEquals(3, grown.getDaysUnpatched());
        assertNotSame(task, grown);
        assertEquals(100, AgeGrowth.apply(task(100, 0), 0.1).getValue());
        assertEquals(120, AgeGrowth.apply(task(100, 2), 0.1).getValue());
        assertEquals(100, AgeGrowth.apply(task(100, 2), 0.0).getValue());
    }

    @Test
    void rejectsInvalidGrowthInputAndOverflow() {
        for (double rate : new double[]{-0.1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> AgeGrowth.apply(task(1, 1), rate));
        }
        assertThrows(IllegalArgumentException.class, () -> AgeGrowth.apply(task(-1, 1), 0.1));
        assertThrows(IllegalArgumentException.class, () -> AgeGrowth.apply(task(1, -1), 0.1));
        assertThrows(IllegalArgumentException.class, () -> AgeGrowth.apply(task(Integer.MAX_VALUE, 1), 1.0));
        assertThrows(IllegalArgumentException.class, () -> AgeGrowth.apply(task(10, 2), Double.MAX_VALUE));
    }

    private static MitigationTask task(int value, int days) {
        return new MitigationTask("task", new Server("host", "Host", "10.0.0.1", 1), value, 1, "", days);
    }
}
