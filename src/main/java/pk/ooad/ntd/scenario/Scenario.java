package pk.ooad.ntd.scenario;

import pk.ooad.ntd.domain.AnalystTeam;
import pk.ooad.ntd.domain.AssetStatus;
import pk.ooad.ntd.domain.Link;
import pk.ooad.ntd.domain.MitigationTask;
import pk.ooad.ntd.domain.Network;
import pk.ooad.ntd.domain.NetworkAsset;
import pk.ooad.ntd.domain.Router;
import pk.ooad.ntd.domain.Server;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Validated, immutable input data, independent of mutable live network state. */
public record Scenario(String id, String name, String socHostId, List<Host> hosts,
                       List<Edge> links, List<Team> teams, List<Task> tasks) {
    public Scenario {
        requireText(id, "id");
        requireText(name, "name");
        requireText(socHostId, "socHostId");
        hosts = copyList(hosts, "hosts");
        links = copyList(links, "links");
        teams = copyList(teams, "teams");
        tasks = copyList(tasks, "tasks");
        if (hosts.isEmpty()) {
            throw new IllegalArgumentException("hosts must not be empty");
        }
        Set<String> hostIds = uniqueValues(hosts, Host::id, "hosts.id");
        Set<String> hostNames = uniqueValues(hosts, Host::name, "hosts.name");
        uniqueValues(links, Edge::id, "links.id");
        uniqueValues(teams, Team::id, "teams.id");
        uniqueValues(tasks, Task::id, "tasks.id");
        if (!hostIds.contains(socHostId)) {
            throw new IllegalArgumentException("socHostId must identify an existing host: " + socHostId);
        }
        if (teams.size() != 3 && teams.size() != 4) {
            throw new IllegalArgumentException("teams must contain exactly 3 or 4 teams");
        }
        uniqueValues(teams, Team::capacity, "teams.capacity");
        for (Edge edge : links) {
            if (!hostIds.contains(edge.sourceId())) {
                throw new IllegalArgumentException("links.sourceId for " + edge.id()
                        + " must identify an existing host: " + edge.sourceId());
            }
            if (!hostIds.contains(edge.targetId())) {
                throw new IllegalArgumentException("links.targetId for " + edge.id()
                        + " must identify an existing host: " + edge.targetId());
            }
        }
        for (Task task : tasks) {
            if (!hostNames.contains(task.hostName())) {
                throw new IllegalArgumentException("tasks.hostName for " + task.id()
                        + " must identify an existing host name: " + task.hostName());
            }
        }
    }

    public record Host(String id, String name, int riskScore, int criticality) {
        public Host {
            requireText(id, "hosts.id");
            requireText(name, "hosts.name");
            requireScore(riskScore, "hosts.riskScore");
            requireScore(criticality, "hosts.criticality");
        }
    }

    public record Edge(String id, String sourceId, String targetId, double cost, boolean compromised) {
        public Edge {
            requireText(id, "links.id");
            requireText(sourceId, "links.sourceId");
            requireText(targetId, "links.targetId");
            if (sourceId.equals(targetId)) {
                throw new IllegalArgumentException("links.sourceId and links.targetId must be distinct for " + id);
            }
            if (!Double.isFinite(cost) || cost <= 0) {
                throw new IllegalArgumentException("links.cost must be positive and finite for " + id);
            }
        }
    }

    public record Team(String id, int capacity) {
        public Team {
            requireText(id, "teams.id");
            if (capacity <= 0) {
                throw new IllegalArgumentException("teams.capacity must be a positive integer for " + id);
            }
        }
    }

    public record Task(String id, String hostName, int effortHours, int baseValue, int daysUnpatched) {
        public Task {
            requireText(id, "tasks.id");
            requireText(hostName, "tasks.hostName");
            if (effortHours <= 0) {
                throw new IllegalArgumentException("tasks.effortHours must be a positive integer for " + id);
            }
            if (baseValue < 0) {
                throw new IllegalArgumentException("tasks.baseValue must be nonnegative for " + id);
            }
            if (daysUnpatched < 0) {
                throw new IllegalArgumentException("tasks.daysUnpatched must be nonnegative for " + id);
            }
        }
    }

    /** Each call constructs new assets and links; the scenario never owns live objects. */
    public Network toNetwork() {
        Network network = new Network();
        for (int i = 0; i < hosts.size(); i++) {
            Host host = hosts.get(i);
            String ip = "10.42.0." + (i + 1);
            NetworkAsset asset = host.id().equals(socHostId)
                    ? new Router(host.id(), host.name(), ip, host.criticality())
                    : new Server(host.id(), host.name(), ip, host.criticality());
            asset.addRisk(host.riskScore());
            asset.setStatus(AssetStatus.NORMAL);
            network.addAsset(asset);
        }
        for (Edge edge : links) {
            Link link = new Link(edge.id(), network.getAsset(edge.sourceId()),
                    network.getAsset(edge.targetId()), edge.cost(), 10.0);
            if (edge.compromised()) link.markCompromised();
            network.addLink(link);
        }
        return network;
    }

    public List<MitigationTask> toTasks(Network network) {
        if (network == null) {
            throw new IllegalArgumentException("network must not be null when resolving tasks.hostName");
        }
        Map<String, NetworkAsset> byName = new HashMap<>();
        for (NetworkAsset asset : network.getAssets()) {
            if (byName.putIfAbsent(asset.getName(), asset) != null) {
                throw new IllegalArgumentException("network host names must be unique: " + asset.getName());
            }
        }
        return tasks.stream().map(task -> {
            NetworkAsset target = byName.get(task.hostName());
            if (target == null) {
                throw new IllegalArgumentException("tasks.hostName for " + task.id()
                        + " is missing from network: " + task.hostName());
            }
            return new MitigationTask(task.id(), target, task.baseValue(), task.effortHours(),
                    "", task.daysUnpatched());
        }).toList();
    }

    public List<AnalystTeam> toTeams() {
        return teams.stream().map(team ->
                new AnalystTeam(team.id(), team.id(), Set.of(), team.capacity(), true)).toList();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be null or blank");
        }
    }

    private static void requireScore(int value, String field) {
        if (value < 1 || value > 10) {
            throw new IllegalArgumentException(field + " must be an integer in 1..10");
        }
    }

    private static <T> List<T> copyList(List<T> values, String field) {
        if (values == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) == null) {
                throw new IllegalArgumentException(field + "[" + i + "] must not be null");
            }
        }
        return List.copyOf(values);
    }

    private static <T, V> Set<V> uniqueValues(List<T> values, Function<T, V> key, String field) {
        Set<V> seen = new HashSet<>();
        for (T value : values) {
            V identifier = key.apply(value);
            if (!seen.add(identifier)) {
                throw new IllegalArgumentException(field + " must be unique; duplicate: " + identifier);
            }
        }
        return seen;
    }
}
