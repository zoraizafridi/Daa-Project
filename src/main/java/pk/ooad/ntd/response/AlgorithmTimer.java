package pk.ooad.ntd.response;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class AlgorithmTimer {
    public record Measurement<T>(T result, long medianNanos, int warmupRuns, int measuredRuns) {
    }

    private record Sample<T>(T result, long nanos) {
    }

    private AlgorithmTimer() {
    }

    public static <T> Measurement<T> measure(Supplier<T> algorithm) {
        Objects.requireNonNull(algorithm, "algorithm must not be null");
        algorithm.get();
        List<Sample<T>> samples = new ArrayList<>(5);
        for (int i = 0; i < 5; i++) {
            long start = System.nanoTime();
            T result = algorithm.get();
            long elapsed = System.nanoTime() - start;
            samples.add(new Sample<>(result, elapsed));
        }
        samples.sort(Comparator.comparingLong(Sample::nanos));
        Sample<T> median = samples.get(2);
        return new Measurement<>(median.result(), median.nanos(), 1, 5);
    }
}
