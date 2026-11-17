package pk.ooad.ntd.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import pk.ooad.ntd.domain.AnalystTeam;
import pk.ooad.ntd.domain.AssetStatus;
import pk.ooad.ntd.domain.AssignmentResult;
import pk.ooad.ntd.domain.BackboneResult;
import pk.ooad.ntd.domain.Link;
import pk.ooad.ntd.domain.LinkStatus;
import pk.ooad.ntd.domain.MitigationTask;
import pk.ooad.ntd.domain.Network;
import pk.ooad.ntd.domain.NetworkAsset;
import pk.ooad.ntd.domain.Router;
import pk.ooad.ntd.domain.Server;
import pk.ooad.ntd.response.AssignmentPlanner;
import pk.ooad.ntd.response.BackbonePlanner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioLoaderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VALID = """
            {
              "id": "test", "name": "Test scenario", "socHostId": "soc",
              "hosts": [
                {"id": "soc", "name": "SOC", "riskScore": 1, "criticality": 5},
                {"id": "host", "name": "Host", "riskScore": 10, "criticality": 2}
              ],
              "links": [
                {"id": "link", "sourceId": "soc", "targetId": "host", "cost": 1.5, "compromised": false}
              ],
              "teams": [
                {"id": "team-3", "capacity": 3},
                {"id": "team-2", "capacity": 2},
                {"id": "team-1", "capacity": 1}
              ],
              "tasks": [
                {"id": "task", "hostName": "Host", "effortHours": 1, "baseValue": 0, "daysUnpatched": 0}
              ]
            }
            """;

    @Test
    void fourBundledScenariosHaveStableIdsCountsAndSerializationRoundTrips() throws IOException {
        List<String> ids = List.of("main-12-hosts-22-links", "disconnected", "over-demand", "oversized-task");
        assertEquals(List.of("main-12-hosts-22-links", "disconnected", "over-demand", "oversized-task",
                ScenarioGenerator.LARGE_ID), ScenarioLoader.availableIds());
        assertThrows(UnsupportedOperationException.class, () -> ScenarioLoader.availableIds().add("other"));
        int[][] counts = {{12, 22, 3, 5}, {5, 2, 3, 3}, {4, 3, 3, 4}, {3, 2, 3, 3}};
        for (int i = 0; i < ids.size(); i++) {
            Scenario first = ScenarioLoader.load(ids.get(i));
            Scenario second = ScenarioLoader.load(ids.get(i));
            assertEquals(ids.get(i), first.id());
            assertEquals(first, second);
            assertNotSame(first, second);
            assertEquals(counts[i][0], first.hosts().size());
            assertEquals(counts[i][1], first.links().size());
            assertEquals(counts[i][2], first.teams().size());
            assertEquals(counts[i][3], first.tasks().size());
            String serialized = JSON.writeValueAsString(first);
            assertEquals(serialized, JSON.writeValueAsString(second));
            assertEquals(first, ScenarioLoader.read(serialized));
            assertEquals(assetIds(first.toNetwork()), assetIds(second.toNetwork()));
            assertEquals(linkIds(first.toNetwork().getLinks()), linkIds(second.toNetwork().getLinks()));
        }
    }

    @Test
    void mainExactlyPreservesFixtureIdentifiersTopologyAndTaskData() {
        Scenario main = ScenarioLoader.load("main-12-hosts-22-links");
        assertEquals("soc", main.socHostId());
        for (int i = 0; i < main.hosts().size(); i++) {
            Scenario.Host host = main.hosts().get(i);
            assertEquals(hostId(i), host.id());
            assertEquals(i == 0 ? "SOC" : String.format(Locale.ROOT, "Host %02d", i), host.name());
            assertEquals(i == 10 ? 10 : i == 11 ? 9 : 1, host.riskScore());
            assertEquals(i == 0 ? 5 : 2, host.criticality());
        }
        for (int i = 0; i < 11; i++) {
            assertEquals(new Scenario.Edge(String.format(Locale.ROOT, "chain-%02d", i + 1),
                    hostId(i), hostId(i + 1), i + 1, false), main.links().get(i));
        }
        int[][] pairs = {{0, 2}, {0, 3}, {1, 3}, {2, 4}, {3, 5}, {4, 6},
                {5, 7}, {6, 8}, {7, 9}, {8, 10}, {9, 11}};
        for (int i = 0; i < pairs.length; i++) {
            assertEquals(new Scenario.Edge(String.format(Locale.ROOT, "extra-%02d", i + 1),
                    hostId(pairs[i][0]), hostId(pairs[i][1]), 20 + i, false), main.links().get(i + 11));
        }
        assertEquals(List.of(new Scenario.Team("team-6", 6), new Scenario.Team("team-4", 4),
                new Scenario.Team("team-3", 3)), main.teams());
        assertEquals(List.of(
                new Scenario.Task("task-host-01", "Host 01", 4, 100, 0),
                new Scenario.Task("task-host-02", "Host 02", 3, 70, 0),
                new Scenario.Task("task-host-03", "Host 03", 2, 30, 0),
                new Scenario.Task("task-host-10", "Host 10", 6, 100, 2),
                new Scenario.Task("task-host-11", "Host 11", 3, 40, 0)), main.tasks());
    }

    @Test
    void mainManualGrowthAndExistingPlannersProve290To200And66To45WithoutMutation() {
        Scenario main = ScenarioLoader.load("main-12-hosts-22-links");
        Network original = main.toNetwork();
        List<MitigationTask> baseTasks = main.toTasks(original);
        BackbonePlanner backbone = new BackbonePlanner();
        AssignmentPlanner assignment = new AssignmentPlanner();
        BackboneResult before = backbone.plan(original, main.socHostId());
        AssignmentResult assignedBefore = assignment.plan(manuallyGrow(baseTasks), main.toTeams());
        assertTrue(before.isConnected());
        assertEquals(66.0, before.getTotalWeight());
        assertEquals(chainIds(11), linkIds(before.getChosenLinks()));
        assertEquals(290, assignedBefore.getTotalValue());
        assertEquals(List.of("task-host-01", "task-host-02", "task-host-10"), assignedIds(assignedBefore));
        assertEquals(120, assignedBefore.getAssignments().values().stream().flatMap(List::stream)
                .filter(task -> task.getId().equals("task-host-10")).findFirst().orElseThrow().getValue());

        Network reduced = main.toNetwork();
        List<MitigationTask> reducedTasks = main.toTasks(reduced);
        assertTrue(reduced.removeAsset("host-10"));
        assertTrue(reduced.removeAsset("host-11"));
        reducedTasks = reducedTasks.stream()
                .filter(task -> reduced.getAsset(task.getTarget().getId()) != null).toList();
        BackboneResult after = backbone.plan(reduced, main.socHostId());
        AssignmentResult assignedAfter = assignment.plan(manuallyGrow(reducedTasks), main.toTeams());
        assertTrue(after.isConnected());
        assertEquals(45.0, after.getTotalWeight());
        assertEquals(chainIds(9), linkIds(after.getChosenLinks()));
        assertEquals(200, assignedAfter.getTotalValue());
        assertEquals(List.of("task-host-01", "task-host-02", "task-host-03"), assignedIds(assignedAfter));

        assertEquals(12, original.getAssets().size());
        assertEquals(22, original.getLinks().size());
        assertEquals(10.0, original.getAsset("host-10").getRiskScore());
        assertEquals(9.0, original.getAsset("host-11").getRiskScore());
        assertEquals(100, baseTasks.get(3).getValue());
        assertEquals(2, baseTasks.get(3).getDaysUnpatched());
        assertEquals(main, ScenarioLoader.load(main.id()));
        assertEquals(66.0, backbone.plan(original, main.socHostId()).getTotalWeight());
    }

    @Test
    void disconnectedHasTwoComponentsAndAnIsolatedHostWithTasksOnEach() {
        Scenario scenario = ScenarioLoader.load("disconnected");
        Network network = scenario.toNetwork();
        BackboneResult result = new BackbonePlanner().plan(network, scenario.socHostId());
        assertFalse(network.isConnected());
        assertFalse(result.isConnected());
        assertEquals(2, result.getChosenLinks().size());
        assertEquals(3.0, result.getTotalWeight());
        assertEquals(List.of("isolated", "remote-a", "remote-b"), assetIds(result.getUnreachable()));
        assertTrue(network.getNeighbours(network.getAsset("isolated")).isEmpty());
        Set<String> unreachable = Set.copyOf(assetIds(result.getUnreachable()));
        List<MitigationTask> tasks = scenario.toTasks(network);
        assertEquals(2, tasks.stream().filter(task -> unreachable.contains(task.getTarget().getId())).count());
        List<MitigationTask> reachable = tasks.stream()
                .filter(task -> !unreachable.contains(task.getTarget().getId())).toList();
        assertEquals(List.of("task-reachable"), reachable.stream().map(MitigationTask::getId).toList());
        assertEquals(25, new AssignmentPlanner().plan(reachable, scenario.toTeams()).getTotalValue());
    }

    @Test
    void overDemandFitsIndividualTasksButOptimallyAssignsOnlyPositivePartialWork() {
        Scenario scenario = ScenarioLoader.load("over-demand");
        int max = scenario.teams().stream().mapToInt(Scenario.Team::capacity).max().orElseThrow();
        int capacity = scenario.teams().stream().mapToInt(Scenario.Team::capacity).sum();
        assertTrue(scenario.tasks().stream().allMatch(task -> task.effortHours() <= max));
        assertTrue(scenario.tasks().stream().mapToInt(Scenario.Task::effortHours).sum() > capacity);
        AssignmentResult result = new AssignmentPlanner().plan(scenario.toTasks(scenario.toNetwork()), scenario.toTeams());
        assertEquals(100, result.getTotalValue());
        assertEquals(List.of("task-a", "task-b", "task-d"), assignedIds(result));
        assertTrue(result.getExcluded().isEmpty());
        assertTrue(assignedIds(result).size() < scenario.tasks().size());
    }

    @Test
    void oversizedTaskIsExcludedWhileFittingTasksAreAssigned() {
        Scenario scenario = ScenarioLoader.load("oversized-task");
        int max = scenario.teams().stream().mapToInt(Scenario.Team::capacity).max().orElseThrow();
        assertEquals(1, scenario.tasks().stream().filter(task -> task.effortHours() > max).count());
        AssignmentResult result = new AssignmentPlanner().plan(scenario.toTasks(scenario.toNetwork()), scenario.toTeams());
        assertEquals(45, result.getTotalValue());
        assertEquals(List.of("task-fitting-a", "task-fitting-b"), assignedIds(result));
        assertEquals(List.of("task-oversized"), result.getExcluded().stream().map(MitigationTask::getId).toList());
    }

    @Test
    void collectionsAreDefensivelyCopiedAndUnmodifiable() {
        Scenario base = ScenarioLoader.read(VALID);
        List<Scenario.Host> hosts = new ArrayList<>(base.hosts());
        List<Scenario.Edge> links = new ArrayList<>(base.links());
        List<Scenario.Team> teams = new ArrayList<>(base.teams());
        List<Scenario.Task> tasks = new ArrayList<>(base.tasks());
        Scenario copy = new Scenario(base.id(), base.name(), base.socHostId(), hosts, links, teams, tasks);
        hosts.clear();
        links.clear();
        teams.clear();
        tasks.clear();
        assertEquals(base, copy);
        assertThrows(UnsupportedOperationException.class, () -> copy.hosts().clear());
        assertThrows(UnsupportedOperationException.class, () -> copy.links().clear());
        assertThrows(UnsupportedOperationException.class, () -> copy.teams().clear());
        assertThrows(UnsupportedOperationException.class, () -> copy.tasks().clear());
        assertThrows(UnsupportedOperationException.class, () -> copy.toTeams().clear());
        assertThrows(UnsupportedOperationException.class, () -> copy.toTasks(copy.toNetwork()).clear());
    }

    @Test
    void conversionCopiesRiskCriticalityStatusLinksAndSkillFreeTeamsIntoFreshObjects() {
        Scenario scenario = ScenarioLoader.read(edited(root -> first(root, "links").put("compromised", true)));
        Network first = scenario.toNetwork();
        Network second = scenario.toNetwork();
        assertInstanceOf(Router.class, first.getAsset("soc"));
        assertInstanceOf(Server.class, first.getAsset("host"));
        assertNotSame(first.getAsset("host"), second.getAsset("host"));
        for (int i = 0; i < scenario.hosts().size(); i++) {
            Scenario.Host declared = scenario.hosts().get(i);
            NetworkAsset asset = first.getAsset(declared.id());
            assertEquals("10.42.0." + (i + 1), asset.getIp());
            assertEquals(declared.name(), asset.getName());
            assertEquals((double) declared.riskScore(), asset.getRiskScore());
            assertEquals(declared.criticality(), asset.getCriticality());
            assertEquals(AssetStatus.NORMAL, asset.getStatus());
        }
        assertNotSame(first.getLinks().get(0), second.getLinks().get(0));
        assertEquals("link", first.getLinks().get(0).getId());
        assertSame(first.getAsset("soc"), first.getLinks().get(0).getSource());
        assertSame(first.getAsset("host"), first.getLinks().get(0).getTarget());
        assertEquals(1.5, first.getLinks().get(0).getBaseCost());
        assertEquals(LinkStatus.COMPROMISED, first.getLinks().get(0).getStatus());
        assertEquals(LinkStatus.NORMAL, ScenarioLoader.read(VALID).toNetwork().getLinks().get(0).getStatus());
        List<AnalystTeam> teams = scenario.toTeams();
        List<AnalystTeam> otherTeams = scenario.toTeams();
        for (int i = 0; i < teams.size(); i++) {
            assertNotSame(teams.get(i), otherTeams.get(i));
            assertEquals(scenario.teams().get(i).id(), teams.get(i).getId());
            assertEquals(teams.get(i).getId(), teams.get(i).getName());
            assertEquals(scenario.teams().get(i).capacity(), teams.get(i).getCapacity());
            assertEquals(Set.of(), teams.get(i).getSkills());
            assertTrue(teams.get(i).isAvailable());
        }
        first.getAsset("host").addRisk(80);
        first.getAsset("host").setStatus(AssetStatus.COMPROMISED);
        first.getLinks().get(0).reset();
        teams.get(0).setCapacity(99);
        assertEquals(10.0, second.getAsset("host").getRiskScore());
        assertEquals(AssetStatus.NORMAL, second.getAsset("host").getStatus());
        assertEquals(LinkStatus.COMPROMISED, second.getLinks().get(0).getStatus());
        assertEquals(3, otherTeams.get(0).getCapacity());
        assertEquals(10, scenario.hosts().get(1).riskScore());
        // Scenario validation does not change the existing live asset's 0..100 risk range.
        assertEquals(90.0, first.getAsset("host").getRiskScore());
    }

    @Test
    void tasksResolveByUniqueNameNotIdAndPreserveBaseFieldsWithoutGrowth() {
        Scenario scenario = ScenarioLoader.load("main-12-hosts-22-links");
        Network renamedIds = new Network();
        for (Scenario.Host host : scenario.hosts()) {
            renamedIds.addAsset(new Server("different-" + host.id(), host.name(), "", host.criticality()));
        }
        List<MitigationTask> first = scenario.toTasks(renamedIds);
        List<MitigationTask> second = scenario.toTasks(renamedIds);
        for (int i = 0; i < first.size(); i++) {
            Scenario.Task task = scenario.tasks().get(i);
            MitigationTask converted = first.get(i);
            assertNotSame(converted, second.get(i));
            assertEquals(task.id(), converted.getId());
            assertSame(renamedIds.getAsset("different-" + scenario.hosts().stream()
                    .filter(host -> host.name().equals(task.hostName())).findFirst().orElseThrow().id()), converted.getTarget());
            assertEquals(task.effortHours(), converted.getCost());
            assertEquals(task.baseValue(), converted.getValue());
            assertEquals(task.daysUnpatched(), converted.getDaysUnpatched());
            assertEquals("", converted.getRequiredSkill());
        }
        assertThrows(IllegalArgumentException.class, () -> scenario.toTasks(null));
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> scenario.toTasks(new Network()));
        assertTrue(missing.getMessage().contains("tasks.hostName"));
        renamedIds.addAsset(new Server("duplicate", "Host 01", "", 2));
        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> scenario.toTasks(renamedIds));
        assertTrue(duplicate.getMessage().contains("unique"));
    }

    @Test
    void rejectsMalformedJsonNullRootsDuplicateKeysAndTrailingTokens() {
        for (String json : Arrays.asList(null, "", " ", "null", "[]", "{", "true", "42")) {
            reject(json, "scenario JSON");
        }
        reject(VALID.replace("\"id\": \"test\"", "\"id\": \"test\", \"id\": \"other\""), "Duplicate");
        reject(VALID.replace("\"riskScore\": 1,", "\"riskScore\": 1, \"riskScore\": 2,"), "riskScore");
        for (String trailing : List.of(" {}", " null", " 42", " true")) {
            reject(VALID + trailing, "Trailing token");
        }
        reject(VALID.replace("1.5", "NaN"), "NaN");
        reject(VALID.replace("1.5", "Infinity"), "Infinity");
    }

    @Test
    void rejectsUnknownFieldsAtEveryRecordLevelIncludingPrematureAgeRate() {
        reject(edited(root -> root.put("ageGrowthRate", 0.1)), "ageGrowthRate");
        for (String collection : List.of("hosts", "links", "teams", "tasks")) {
            reject(edited(root -> first(root, collection).put("unknown", 1)), "unknown");
        }
    }

    @Test
    void rejectsEveryMissingOrNullRecordFieldAndNullCollectionElement() throws IOException {
        ObjectNode template = (ObjectNode) JSON.readTree(VALID);
        List<String> rootFields = new ArrayList<>();
        template.fieldNames().forEachRemaining(rootFields::add);
        for (String field : rootFields) {
            reject(edited(root -> root.remove(field)), field);
            reject(edited(root -> root.putNull(field)), field);
        }
        for (String collection : List.of("hosts", "links", "teams", "tasks")) {
            List<String> fields = new ArrayList<>();
            first(template, collection).fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                reject(edited(root -> first(root, collection).remove(field)), field);
                reject(edited(root -> first(root, collection).putNull(field)), field);
            }
            reject(edited(root -> ((ArrayNode) root.get(collection)).addNull()), collection);
        }
    }

    @Test
    void rejectsBlankTextDuplicateIdentifiersAndDuplicateHostNames() {
        for (String field : List.of("id", "name", "socHostId")) {
            reject(edited(root -> root.put(field, " \t")), field);
        }
        String[][] textFields = {{"hosts", "id"}, {"hosts", "name"}, {"links", "id"},
                {"links", "sourceId"}, {"links", "targetId"}, {"teams", "id"},
                {"tasks", "id"}, {"tasks", "hostName"}};
        for (String[] field : textFields) {
            reject(edited(root -> first(root, field[0]).put(field[1], "")), field[1]);
        }
        for (String collection : List.of("hosts", "links", "teams", "tasks")) {
            reject(edited(root -> ((ArrayNode) root.get(collection)).add(first(root, collection).deepCopy())), "unique");
        }
        reject(edited(root -> ((ObjectNode) root.get("hosts").get(1)).put("name", "SOC")), "hosts.name");
    }

    @Test
    void rejectsInvalidSocMissingEndpointsSelfLoopsAndMissingTaskHost() {
        reject(edited(root -> ((ArrayNode) root.get("hosts")).removeAll()), "hosts");
        reject(edited(root -> root.put("socHostId", "missing")), "socHostId");
        reject(edited(root -> first(root, "links").put("sourceId", "missing")), "sourceId");
        reject(edited(root -> first(root, "links").put("targetId", "missing")), "targetId");
        reject(edited(root -> first(root, "links").put("targetId", "soc")), "distinct");
        reject(edited(root -> first(root, "tasks").put("hostName", "missing")), "hostName");
    }

    @Test
    void rejectsNumericRangesAndAllowsZeroBaseValueAndZeroAge() {
        for (String field : List.of("riskScore", "criticality")) {
            for (int invalid : new int[]{-1, 0, 11}) {
                reject(edited(root -> first(root, "hosts").put(field, invalid)), field);
            }
        }
        for (double invalid : new double[]{-1, 0}) {
            reject(edited(root -> first(root, "links").put("cost", invalid)), "cost");
        }
        reject(VALID.replace("1.5", "1e309"), "cost");
        reject(VALID.replace("1.5", "-1e309"), "cost");
        for (int invalid : new int[]{-1, 0}) {
            reject(edited(root -> first(root, "tasks").put("effortHours", invalid)), "effortHours");
            reject(edited(root -> first(root, "teams").put("capacity", invalid)), "capacity");
        }
        reject(edited(root -> first(root, "tasks").put("baseValue", -1)), "baseValue");
        reject(edited(root -> first(root, "tasks").put("daysUnpatched", -1)), "daysUnpatched");
        Scenario valid = ScenarioLoader.read(VALID);
        assertEquals(0, valid.tasks().get(0).baseValue());
        assertEquals(0, valid.tasks().get(0).daysUnpatched());
    }

    @Test
    void rejectsNonintegerNonnumericAndCoercedScalarFields() {
        String[][] integerFields = {{"hosts", "riskScore"}, {"hosts", "criticality"}, {"teams", "capacity"},
                {"tasks", "effortHours"}, {"tasks", "baseValue"}, {"tasks", "daysUnpatched"}};
        for (String[] field : integerFields) {
            for (double floating : new double[]{1.0, 1.5}) {
                reject(edited(root -> first(root, field[0]).put(field[1], floating)), field[1]);
            }
            for (String text : List.of("1", "nope", "")) {
                reject(edited(root -> first(root, field[0]).put(field[1], text)), field[1]);
            }
            reject(edited(root -> first(root, field[0]).put(field[1], true)), field[1]);
            reject(edited(root -> first(root, field[0]).put(field[1], 2147483648L)), field[1]);
        }
        for (String text : List.of("1.5", "nope", "NaN", "Infinity", "")) {
            reject(edited(root -> first(root, "links").put("cost", text)), "cost");
        }
        reject(edited(root -> first(root, "links").put("cost", true)), "cost");
        reject(edited(root -> first(root, "links").put("compromised", "false")), "compromised");
        reject(edited(root -> first(root, "links").put("compromised", 0)), "compromised");
        reject(edited(root -> root.put("id", 1)), "id");
        reject(edited(root -> first(root, "hosts").put("name", true)), "name");
    }

    @Test
    void teamCountIsExactlyThreeOrFourWithDistinctPositiveCapacities() {
        for (int count : new int[]{0, 1, 2, 5}) {
            reject(edited(root -> {
                ArrayNode teams = ((ArrayNode) root.get("teams")).removeAll();
                for (int i = 1; i <= count; i++) teams.addObject().put("id", "team-" + i).put("capacity", i);
            }), "exactly 3 or 4");
        }
        reject(edited(root -> first(root, "teams").put("capacity", 2)), "teams.capacity");
        Scenario four = ScenarioLoader.read(edited(root ->
                ((ArrayNode) root.get("teams")).addObject().put("id", "team-4").put("capacity", 4)));
        assertEquals(4, four.teams().size());
        Scenario emptyWork = ScenarioLoader.read(edited(root -> {
            ((ArrayNode) root.get("links")).removeAll();
            ((ArrayNode) root.get("tasks")).removeAll();
        }));
        assertTrue(emptyWork.links().isEmpty());
        assertTrue(emptyWork.tasks().isEmpty());
    }

    @Test
    void recordConstructorsAlsoValidateDirectExternalInput() {
        assertThrows(IllegalArgumentException.class, () -> new Scenario.Host(null, "Host", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Scenario.Host("id", " ", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Scenario.Host("id", "Host", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Scenario.Host("id", "Host", 1, 11));
        assertEquals(1, new Scenario.Host("low", "Low", 1, 1).riskScore());
        assertEquals(10, new Scenario.Host("high", "High", 10, 10).criticality());
        for (double cost : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0, -1}) {
            assertThrows(IllegalArgumentException.class, () -> new Scenario.Edge("edge", "a", "b", cost, false));
        }
        assertThrows(IllegalArgumentException.class, () -> new Scenario.Edge("edge", "a", "a", 1, false));
        assertThrows(IllegalArgumentException.class, () -> new Scenario.Team("team", 0));
        assertThrows(IllegalArgumentException.class, () -> new Scenario.Task("task", "Host", 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Scenario.Task("task", "Host", 1, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Scenario.Task("task", "Host", 1, 0, -1));
        Scenario base = ScenarioLoader.read(VALID);
        assertThrows(IllegalArgumentException.class, () -> new Scenario(null, base.name(), base.socHostId(),
                base.hosts(), base.links(), base.teams(), base.tasks()));
        assertThrows(IllegalArgumentException.class, () -> new Scenario(base.id(), base.name(), "missing",
                base.hosts(), base.links(), base.teams(), base.tasks()));
        assertThrows(IllegalArgumentException.class, () -> new Scenario(base.id(), base.name(), base.socHostId(),
                null, base.links(), base.teams(), base.tasks()));
        assertThrows(IllegalArgumentException.class, () -> new Scenario(base.id(), base.name(), base.socHostId(),
                base.hosts(), null, base.teams(), base.tasks()));
        assertThrows(IllegalArgumentException.class, () -> new Scenario(base.id(), base.name(), base.socHostId(),
                base.hosts(), base.links(), null, base.tasks()));
        assertThrows(IllegalArgumentException.class, () -> new Scenario(base.id(), base.name(), base.socHostId(),
                base.hosts(), base.links(), base.teams(), null));
        assertThrows(IllegalArgumentException.class, () -> new Scenario(base.id(), base.name(), base.socHostId(),
                Arrays.asList(base.hosts().get(0), null), base.links(), base.teams(), base.tasks()));
    }

    @Test
    void classpathLoadingRejectsUnknownIdsAndEveryPathLikeSelector() {
        for (String id : Arrays.asList(null, "", "unknown", "../config.properties", "../../pom.xml",
                "/scenarios/disconnected.json", "scenarios/disconnected", "disconnected.json",
                "..\\config.properties", "E:/daa/Network-Threat-Detection--main/pom.xml",
                "%2e%2e/config.properties", " disconnected", "DISCONNECTED")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ScenarioLoader.load(id));
            assertTrue(error.getMessage().contains("scenario id"));
        }
    }

    private static List<MitigationTask> manuallyGrow(List<MitigationTask> tasks) {
        return tasks.stream().map(task -> new MitigationTask(task.getId(), task.getTarget(),
                (int) Math.round(task.getValue() * (1.0 + 0.1 * task.getDaysUnpatched())),
                task.getCost(), "", task.getDaysUnpatched())).toList();
    }

    private static String edited(Consumer<ObjectNode> edit) {
        try {
            ObjectNode root = (ObjectNode) JSON.readTree(VALID);
            edit.accept(root);
            return JSON.writeValueAsString(root);
        } catch (IOException e) {
            throw new AssertionError("Unable to construct test input", e);
        }
    }

    private static ObjectNode first(ObjectNode root, String collection) {
        return (ObjectNode) root.get(collection).get(0);
    }

    private static void reject(String json, String problem) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ScenarioLoader.read(json));
        assertTrue(error.getMessage().toLowerCase(Locale.ROOT).contains(problem.toLowerCase(Locale.ROOT)),
                () -> "Expected problem '" + problem + "' in: " + error.getMessage());
    }

    private static String hostId(int index) {
        return index == 0 ? "soc" : String.format(Locale.ROOT, "host-%02d", index);
    }

    private static List<String> chainIds(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> String.format(Locale.ROOT, "chain-%02d", i)).toList();
    }

    private static List<String> assetIds(Network network) {
        return assetIds(new ArrayList<>(network.getAssets()));
    }

    private static List<String> assetIds(List<NetworkAsset> assets) {
        return assets.stream().map(NetworkAsset::getId).toList();
    }

    private static List<String> linkIds(List<Link> links) {
        return links.stream().map(Link::getId).toList();
    }

    private static List<String> assignedIds(AssignmentResult result) {
        return result.getAssignments().values().stream().flatMap(List::stream).map(MitigationTask::getId).sorted().toList();
    }
}
