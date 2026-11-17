package pk.ooad.ntd.response;

import pk.ooad.ntd.domain.MitigationTask;
import pk.ooad.ntd.util.ConfigManager;

public final class AgeGrowth {
    private AgeGrowth() {
    }

    public static double configuredRate() {
        double rate = ConfigManager.getInstance().getDouble("decay.rate", 0.1);
        requireRate(rate);
        return rate;
    }

    public static MitigationTask apply(MitigationTask task, double rate) {
        requireRate(rate);
        if (task.getValue() < 0 || task.getDaysUnpatched() < 0) {
            throw new IllegalArgumentException("Task base value and days unpatched must be nonnegative");
        }
        double grown = task.getValue() * (1.0 + rate * task.getDaysUnpatched());
        if (!Double.isFinite(grown)) {
            throw new IllegalArgumentException("Age-grown value is not finite for task " + task.getId());
        }
        long rounded = Math.round(grown);
        if (rounded > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Age-grown value exceeds integer range for task " + task.getId());
        }
        return new MitigationTask(task.getId(), task.getTarget(), (int) rounded, task.getCost(),
                task.getRequiredSkill(), task.getDaysUnpatched());
    }

    private static void requireRate(double rate) {
        if (!Double.isFinite(rate) || rate < 0) {
            throw new IllegalArgumentException("Linear age growth rate must be finite and nonnegative");
        }
    }
}
