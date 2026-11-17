package pk.ooad.ntd.scenario;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public final class ScenarioGenerator {
    public static final String LARGE_ID = "large-200-hosts-1000-links";

    private ScenarioGenerator() {
    }

    public static Scenario large(long seed) {
        Random random = new Random(seed);
        List<Scenario.Host> hosts = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            hosts.add(new Scenario.Host(hostId(i), i == 0 ? "SOC" : "Host " + i,
                    1 + random.nextInt(10), 1 + random.nextInt(10)));
        }
        List<Scenario.Edge> links = new ArrayList<>();
        Set<Integer> pairs = new HashSet<>();
        for (int i = 1; i < 200; i++) {
            pairs.add((i - 1) * 200 + i);
            links.add(edge(links.size(), i - 1, i, random));
        }
        while (links.size() < 1000) {
            int a = random.nextInt(200);
            int b = random.nextInt(200);
            int source = Math.min(a, b);
            int target = Math.max(a, b);
            if (source != target && pairs.add(source * 200 + target)) {
                links.add(edge(links.size(), source, target, random));
            }
        }
        List<Scenario.Task> tasks = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            tasks.add(new Scenario.Task("task-" + i, "Host " + i,
                    1 + random.nextInt(6), 10 + random.nextInt(91), random.nextInt(8)));
        }
        return new Scenario(LARGE_ID, "200 hosts / 1000 links (seed " + seed + ")", "soc",
                hosts, links, List.of(new Scenario.Team("team-6", 6),
                new Scenario.Team("team-4", 4), new Scenario.Team("team-3", 3)), tasks);
    }

    private static Scenario.Edge edge(int index, int source, int target, Random random) {
        return new Scenario.Edge("edge-" + index, hostId(source), hostId(target),
                1 + random.nextInt(100), false);
    }

    private static String hostId(int index) {
        return index == 0 ? "soc" : "host-" + index;
    }
}
