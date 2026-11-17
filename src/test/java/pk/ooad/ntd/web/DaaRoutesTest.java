package pk.ooad.ntd.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pk.ooad.ntd.domain.TopologyLoader;
import pk.ooad.ntd.service.IncidentResponseFacade;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** HTTP integration contracts, using the same ephemeral WebServer/cookie setup as WebServerTest. */
class DaaRoutesTest {
    private static final String MAIN = "main-12-hosts-22-links";
    private static final String LARGE = "large-200-hosts-1000-links";
    private static final String PREFIX = "/api/daa/";
    private static final List<String> POSTS = List.of("scenarios/load", "backbone", "backbone/repair",
            "assign", "compare", "pipeline", "sensitivity", "edge-cases");
    private static final List<String> SELECTED_POSTS = List.of("backbone", "backbone/repair", "assign",
            "compare", "pipeline", "sensitivity");
    private final ObjectMapper mapper = new ObjectMapper();
    private IncidentResponseFacade facade;
    private WebServer server;
    private HttpClient http;
    private CookieManager cookies;
    private int port;

    @BeforeEach
    void startServer() {
        facade = new IncidentResponseFacade(TopologyLoader.loadSample(), 42L);
        server = new WebServer(facade, 0);
        server.start(0);
        port = server.port();
        cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        http = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(3)).build();
    }

    @AfterEach
    void stopServer() {
        if (facade != null) facade.stop();
        if (server != null) server.stop();
    }

    @Test
    void allNinePathsRequireAuthentication() throws Exception {
        assertEquals(401, call("GET", PREFIX + "scenarios", null).statusCode());
        for (String path : POSTS) {
            assertEquals(401, call("POST", PREFIX + path, bodyFor(path)).statusCode(), path);
        }
    }

    @Test
    void viewerAndAnalystAreForbiddenOnAllNinePaths() throws Exception {
        for (String role : List.of("viewer", "analyst")) {
            login(role);
            assertEquals(403, call("GET", PREFIX + "scenarios", null).statusCode(), role);
            for (String path : POSTS) {
                assertEquals(403, call("POST", PREFIX + path, bodyFor(path)).statusCode(), role + " " + path);
            }
        }
    }

    @Test
    void adminCanUseAllNinePaths() throws Exception {
        login("admin");
        assertEquals(200, call("GET", PREFIX + "scenarios", null).statusCode());
        for (String path : POSTS) {
            assertEquals(200, call("POST", PREFIX + path, bodyFor(path)).statusCode(), path);
        }
    }

    @Test
    void everyPostRequiresMatchingDoubleSubmitCsrf() throws Exception {
        login("admin");
        for (String path : POSTS) {
            assertEquals(403, send(http, PREFIX + path, bodyFor(path), "application/json", null).statusCode(), path);
            assertEquals(403, send(http, PREFIX + path, bodyFor(path), "application/json", "mismatch")
                    .statusCode(), path);
        }
        String headerOnly = csrf(cookies);
        for (HttpCookie cookie : new ArrayList<>(cookies.getCookieStore().getCookies())) {
            if ("csrf".equals(cookie.getName())) cookies.getCookieStore().remove(null, cookie);
        }
        for (String path : POSTS) {
            assertEquals(403, send(http, PREFIX + path, bodyFor(path), "application/json", headerOnly)
                    .statusCode(), path);
        }
        // GET is not CSRF-protected.
        assertEquals(200, call("GET", PREFIX + "scenarios", null).statusCode());
    }

    @Test
    void logoutLosesAccessToEveryDaaPath() throws Exception {
        login("admin");
        assertEquals(200, call("POST", "/api/logout", null).statusCode());
        assertEquals(401, call("GET", PREFIX + "scenarios", null).statusCode());
        for (String path : POSTS) {
            assertEquals(401, call("POST", PREFIX + path, bodyFor(path)).statusCode(), path);
        }
    }

    @Test
    void authorizationUsesEachSessionNotTheLastFacadeLogin() throws Exception {
        login("admin");
        CookieManager otherCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient other = HttpClient.newBuilder().cookieHandler(otherCookies).build();
        assertEquals(200, send(other, "/api/login", credentials("viewer"), "application/json", null).statusCode());
        assertEquals(200, call("POST", PREFIX + "pipeline", selector(MAIN)).statusCode());
        login("admin"); // The global facade user is now Admin, but the other session remains Viewer.
        assertEquals(403, send(other, PREFIX + "pipeline", selector(MAIN), "application/json", csrf(otherCookies))
                .statusCode());
        login("analyst");
        assertEquals(200, send(other, "/api/login", credentials("admin"), "application/json", null).statusCode());
        assertEquals(403, call("POST", PREFIX + "pipeline", selector(MAIN)).statusCode());
        assertEquals(200, send(other, PREFIX + "pipeline", selector(MAIN), "application/json", csrf(otherCookies))
                .statusCode());
    }

    @Test
    void unknownPathsRemain404BeforeAndAfterLogin() throws Exception {
        for (String user : List.of("", "viewer", "analyst", "admin")) {
            if (!user.isEmpty()) login(user);
            for (String path : List.of("nope", "backbone/nope", "scenarios/main", "pipeline/extra")) {
                assertEquals(404, call("GET", PREFIX + path, null).statusCode(), path);
                assertEquals(404, call("POST", PREFIX + path, "{}").statusCode(), path);
            }
        }
    }

    @Test
    void catalogueAndLoadHaveExactScenarioContractsIncludingLarge() throws Exception {
        login("admin");
        JsonNode catalogue = ok("GET", "scenarios", null);
        assertTrue(catalogue.isArray());
        assertEquals(Set.of(MAIN, "disconnected", "over-demand", "oversized-task", LARGE), ids(catalogue));
        for (JsonNode row : catalogue) {
            fields(row, "id", "name", "hostCount", "linkCount", "teamCount", "taskCount");
            assertFalse(row.path("name").asText().isBlank());
            JsonNode scenario = ok("POST", "scenarios/load", selector(row.path("id").asText()));
            scenarioFields(scenario);
            assertEquals(row.path("hostCount").asInt(), scenario.path("hosts").size());
            assertEquals(row.path("linkCount").asInt(), scenario.path("links").size());
            assertEquals(row.path("teamCount").asInt(), scenario.path("teams").size());
            assertEquals(row.path("taskCount").asInt(), scenario.path("tasks").size());
        }
        JsonNode main = loadMain();
        assertEquals(12, main.path("hosts").size());
        assertEquals(22, main.path("links").size());
        JsonNode large = ok("POST", "scenarios/load", selector(LARGE));
        assertEquals(200, large.path("hosts").size());
        assertEquals(1000, large.path("links").size());
    }

    @Test
    void fixtureBackboneHasCost66SortedEdgesCountersAndTimingMetadata() throws Exception {
        login("admin");
        JsonNode response = ok("POST", "backbone", selector(MAIN));
        fields(response, "scenario", "backbone");
        scenarioFields(response.path("scenario"));
        JsonNode backbone = response.path("backbone");
        backboneFields(backbone);
        assertEquals(66.0, backbone.path("totalWeight").asDouble());
        assertTrue(backbone.path("connected").asBoolean());
        assertEquals(11, backbone.path("chosenLinks").size());
        assertEquals(22, backbone.path("sortedCandidates").size());
        assertEquals(0, backbone.path("unreachable").size());
        double previous = -1;
        for (JsonNode edge : backbone.path("sortedCandidates")) {
            assertTrue(edge.path("cost").asDouble() >= previous);
            previous = edge.path("cost").asDouble();
        }
        for (String counter : List.of("comparisons", "findCalls", "unions", "edgesScanned")) {
            assertTrue(backbone.path("operations").path(counter).asLong() > 0, counter);
        }
    }

    @Test
    void assignmentPreservesBaseValuesAndIncludesEveryTeam() throws Exception {
        login("admin");
        JsonNode response = ok("POST", "assign", selector(MAIN));
        fields(response, "scenario", "ageGrowthRate", "tasks", "assignment");
        assertEquals(0.1, response.path("ageGrowthRate").asDouble());
        JsonNode assignment = response.path("assignment");
        assignmentFields(assignment, "DP", 3);
        assertEquals(290, assignment.path("totalValue").asInt());
        assertTrue(assignment.path("transitionOps").asLong() > 0);
        assertTrue(assignment.path("stateCount").asLong() > 0);
        JsonNode aged = byId(response.path("tasks"), "task-host-10");
        taskFields(aged);
        assertEquals("host-10", aged.path("hostId").asText());
        assertEquals("Host 10", aged.path("hostName").asText());
        assertEquals(100, aged.path("baseValue").asInt());
        assertEquals(120, aged.path("adjustedValue").asInt());
        assertEquals(2, aged.path("daysUnpatched").asInt());
        assertEquals(6, aged.path("effortHours").asInt());
    }

    @Test
    void emptyWorkStillIncludesAllFourEmptyTeamRows() throws Exception {
        login("admin");
        ObjectNode scenario = loadMain().deepCopy();
        scenario.putArray("tasks");
        ((ArrayNode) scenario.path("teams")).addObject().put("id", "team-2").put("capacity", 2);
        JsonNode response = ok("POST", "assign", custom(scenario));
        assignmentFields(response.path("assignment"), "DP", 4);
        assertEquals(0, response.path("assignment").path("totalValue").asInt());
        assertEquals(0, response.path("tasks").size());
        for (JsonNode team : response.path("assignment").path("teams")) {
            assertEquals(0, team.path("usedHours").asInt());
            assertEquals(0, team.path("tasks").size());
        }
    }

    @Test
    void comparisonNamesAllThreeSolversAndAgreesWithBruteForce() throws Exception {
        login("admin");
        JsonNode response = ok("POST", "compare", selector(MAIN));
        fields(response, "scenario", "ageGrowthRate", "tasks", "dp", "greedy", "bruteForce", "bruteForceError");
        assignmentFields(response.path("dp"), "DP", 3);
        assignmentFields(response.path("greedy"), "GREEDY", 3);
        assignmentFields(response.path("bruteForce"), "BRUTE_FORCE", 3);
        assertTrue(response.path("bruteForceError").isNull());
        assertEquals(290, response.path("dp").path("totalValue").asInt());
        assertEquals(response.path("dp").path("totalValue"), response.path("bruteForce").path("totalValue"));
        assertTrue(response.path("dp").path("totalValue").asInt()
                >= response.path("greedy").path("totalValue").asInt());
    }

    @Test
    void thirteenSubmittedTasksSkipBruteForceButDpAndGreedySucceed() throws Exception {
        login("admin");
        ObjectNode scenario = thirteenTasks();
        JsonNode response = ok("POST", "compare", custom(scenario));
        assertEquals(13, response.path("tasks").size());
        assertTrue(response.path("bruteForce").isNull());
        String error = response.path("bruteForceError").asText();
        assertTrue(error.contains("12"), error);
        assertTrue(error.contains("13"), error);
        assignmentFields(response.path("dp"), "DP", 3);
        assignmentFields(response.path("greedy"), "GREEDY", 3);
        assertTrue(response.path("dp").path("totalValue").asInt()
                >= response.path("greedy").path("totalValue").asInt());
        // The limit uses submitted count, even if the thirteenth task is overweight.
        ((ObjectNode) scenario.path("tasks").get(12)).put("effortHours", 100);
        assertTrue(ok("POST", "compare", custom(scenario)).path("bruteForce").isNull());
        ((ObjectNode) scenario.path("tasks").get(0)).remove("baseValue");
        bad("compare", custom(scenario), 400);
    }

    @Test
    void pipelineAndSensitivityHaveExactContractsAndFixtureResults() throws Exception {
        login("admin");
        JsonNode pipeline = ok("POST", "pipeline", selector(MAIN));
        pipelineFields(pipeline);
        assertEquals(66.0, pipeline.path("backbone").path("totalWeight").asDouble());
        assertEquals(290, pipeline.path("assignment").path("totalValue").asInt());
        assertEquals(12, pipeline.path("activeHosts").size());
        assertEquals(22, pipeline.path("activeLinks").size());
        JsonNode sensitivity = ok("POST", "sensitivity", selector(MAIN));
        fields(sensitivity, "removedHosts", "before", "after", "removedLinkIds", "addedLinkIds", "costDelta",
                "valueDelta", "newlyIsolatedHosts", "droppedTasks", "newlyAssignedTaskIds",
                "noLongerAssignedTaskIds", "movedTasks");
        pipelineFields(sensitivity.path("before"));
        pipelineFields(sensitivity.path("after"));
        assertEquals(Set.of("host-10", "host-11"), ids(sensitivity.path("removedHosts")));
        assertEquals(66.0, sensitivity.path("before").path("backbone").path("totalWeight").asDouble());
        assertEquals(290, sensitivity.path("before").path("assignment").path("totalValue").asInt());
        assertEquals(45.0, sensitivity.path("after").path("backbone").path("totalWeight").asDouble());
        assertEquals(200, sensitivity.path("after").path("assignment").path("totalValue").asInt());
        assertEquals(-21.0, sensitivity.path("costDelta").asDouble());
        assertEquals(-90, sensitivity.path("valueDelta").asInt());
        assertEquals(10, sensitivity.path("after").path("activeHosts").size());
        assertEquals(Set.of("chain-10", "chain-11"), strings(sensitivity.path("removedLinkIds")));
        assertEquals(0, sensitivity.path("addedLinkIds").size());
        assertEquals(Set.of("task-host-10"), strings(sensitivity.path("noLongerAssignedTaskIds")));
        assertEquals(Set.of("task-host-03"), strings(sensitivity.path("newlyAssignedTaskIds")));
        assertEquals(2, sensitivity.path("droppedTasks").size());
        for (JsonNode drop : sensitivity.path("droppedTasks")) {
            fields(drop, "task", "reason");
            assertEquals("MISSING_HOST", drop.path("reason").asText());
            scenarioTaskFields(drop.path("task"));
        }
        for (JsonNode host : sensitivity.path("newlyIsolatedHosts")) fields(host, "id", "name");
        for (JsonNode moved : sensitivity.path("movedTasks")) fields(moved, "taskId", "fromTeamId", "toTeamId");
    }

    @Test
    void sensitivitySerializesNewlyIsolatedHostNamesAndNeverRemovesSoc() throws Exception {
        login("admin");
        ObjectNode scenario = loadMain().deepCopy();
        scenario.put("id", "bridge-removal");
        ArrayNode hosts = scenario.putArray("hosts");
        hosts.addObject().put("id", "soc").put("name", "SOC").put("riskScore", 10).put("criticality", 10);
        hosts.addObject().put("id", "bridge").put("name", "Bridge").put("riskScore", 9).put("criticality", 5);
        hosts.addObject().put("id", "cut").put("name", "Cut").put("riskScore", 8).put("criticality", 5);
        hosts.addObject().put("id", "leaf").put("name", "Leaf").put("riskScore", 1).put("criticality", 1);
        hosts.addObject().put("id", "old-isolated").put("name", "Old Isolated").put("riskScore", 1).put("criticality", 1);
        ArrayNode links = scenario.putArray("links");
        links.addObject().put("id", "soc-bridge").put("sourceId", "soc").put("targetId", "bridge")
                .put("cost", 1.5).put("compromised", false);
        links.addObject().put("id", "bridge-leaf").put("sourceId", "bridge").put("targetId", "leaf")
                .put("cost", 2).put("compromised", false);
        links.addObject().put("id", "soc-cut").put("sourceId", "soc").put("targetId", "cut")
                .put("cost", 4).put("compromised", false);
        ArrayNode tasks = scenario.putArray("tasks");
        tasks.addObject().put("id", "leaf-task").put("hostName", "Leaf").put("effortHours", 1)
                .put("baseValue", 20).put("daysUnpatched", 0);
        JsonNode response = ok("POST", "sensitivity", custom(scenario));
        assertEquals(Set.of("bridge", "cut"), ids(response.path("removedHosts")));
        assertEquals(Set.of("leaf"), ids(response.path("newlyIsolatedHosts")));
        JsonNode isolated = response.path("newlyIsolatedHosts").get(0);
        fields(isolated, "id", "name");
        assertEquals("Leaf", isolated.path("name").asText());
        assertTrue(ids(response.path("after").path("activeHosts")).contains("soc"));
        assertEquals("OUTSIDE_SOC_COMPONENT", response.path("droppedTasks").get(0).path("reason").asText());
        assertEquals(Set.of("leaf-task"), strings(response.path("noLongerAssignedTaskIds")));
        pipelineFields(response.path("before"));
        pipelineFields(response.path("after"));
    }

    @Test
    void disconnectedBackboneReturnsNamedObjectsAndPipelineDropsOutsideSocWork() throws Exception {
        login("admin");
        JsonNode backbone = ok("POST", "backbone", selector("disconnected")).path("backbone");
        backboneFields(backbone);
        assertFalse(backbone.path("connected").asBoolean());
        assertEquals(Set.of("remote-a", "remote-b", "isolated"), ids(backbone.path("unreachable")));
        assertEquals("Remote A", byId(backbone.path("unreachable"), "remote-a").path("name").asText());
        assertEquals("Isolated", byId(backbone.path("unreachable"), "isolated").path("name").asText());
        JsonNode pipeline = ok("POST", "pipeline", selector("disconnected"));
        pipelineFields(pipeline);
        assertEquals(Set.of("task-reachable"), ids(pipeline.path("tasks")));
        assertEquals(2, pipeline.path("droppedTasks").size());
        for (JsonNode drop : pipeline.path("droppedTasks")) {
            assertEquals("OUTSIDE_SOC_COMPONENT", drop.path("reason").asText());
        }
        assertEquals(Set.of("task-reachable"), assignedIds(pipeline.path("assignment")));
    }

    @Test
    void repairUsesThreeTreeLinksAndDoesNotMutateItsSource() throws Exception {
        login("admin");
        ObjectNode source = loadMain().deepCopy();
        String unchanged = source.toString();
        JsonNode repair = ok("POST", "backbone/repair", custom(source));
        fields(repair, "scenario", "compromisedLinkIds", "original", "incremental", "fullRecompute",
                "sameEdges", "sameCost");
        assertEquals(3, repair.path("compromisedLinkIds").size());
        Set<String> compromised = strings(repair.path("compromisedLinkIds"));
        assertEquals(3, compromised.size());
        assertTrue(ids(repair.path("original").path("chosenLinks")).containsAll(compromised));
        assertTrue(repair.path("sameEdges").asBoolean());
        assertTrue(repair.path("sameCost").asBoolean());
        for (String solver : List.of("original", "incremental", "fullRecompute")) backboneFields(repair.path(solver));
        assertEquals(ids(repair.path("incremental").path("chosenLinks")),
                ids(repair.path("fullRecompute").path("chosenLinks")));
        assertEquals(repair.path("incremental").path("totalWeight"), repair.path("fullRecompute").path("totalWeight"));
        for (JsonNode edge : repair.path("scenario").path("links")) {
            assertEquals(compromised.contains(edge.path("id").asText()), edge.path("compromised").asBoolean());
        }
        assertEquals(unchanged, source.toString());
        assertEquals(source, loadMain());
        assertEquals(66.0, ok("POST", "backbone", custom(source)).path("backbone").path("totalWeight").asDouble());
        JsonNode explicit = ok("POST", "backbone/repair", "{\"scenarioId\":\"" + MAIN
                + "\",\"linkIds\":[\"chain-01\",\"chain-02\",\"chain-03\"]}");
        assertEquals(Set.of("chain-01", "chain-02", "chain-03"), strings(explicit.path("compromisedLinkIds")));
    }

    @Test
    void repairRejectsNullWrongCountDuplicatesTypesMissingAndNonTreeIds() throws Exception {
        login("admin");
        JsonNode before = loadMain();
        for (String links : List.of("null", "[]", "[\"chain-01\"]", "[\"chain-01\",\"chain-02\"]",
                "[\"chain-01\",\"chain-02\",\"chain-03\",\"chain-04\"]",
                "[\"chain-01\",\"chain-01\",\"chain-03\"]", "[\"chain-01\",null,\"chain-03\"]",
                "[\"chain-01\",2,\"chain-03\"]", "[\"chain-01\",\" \",\"chain-03\"]",
                "[\"chain-01\",\"missing\",\"chain-03\"]", "[\"chain-01\",\"extra-01\",\"chain-03\"]",
                "\"chain-01\"")) {
            bad("backbone/repair", "{\"scenarioId\":\"" + MAIN + "\",\"linkIds\":" + links + "}", 400);
        }
        ObjectNode compromisedSource = before.deepCopy();
        ((ObjectNode) compromisedSource.path("links").get(0)).put("compromised", true);
        ObjectNode request = mapper.createObjectNode().set("scenario", compromisedSource);
        request.putArray("linkIds").add("chain-01").add("chain-02").add("chain-03");
        bad("backbone/repair", request.toString(), 400);
        assertEquals(before, loadMain());
        for (String id : List.of("disconnected", "oversized-task")) {
            bad("backbone/repair", selector(id), 400);
        }
    }

    @Test
    void strictSelectorAndJsonValidationAppliesToAllAlgorithmPosts() throws Exception {
        login("admin");
        String main = loadMain().toString();
        List<String> invalid = List.of("", " ", "null", "[]", "42", "\"text\"", "{}", "{",
                "{\"scenarioId\":null}", "{\"scenarioId\":4}", "{\"scenarioId\":true}",
                "{\"scenarioId\":\" \"}", "{\"scenarioId\":\"missing\"}",
                "{\"scenarioId\":\"../main-12-hosts-22-links\"}", "{\"scenario\":null}",
                "{\"scenario\":[]}", "{\"scenario\":\"{}\"}", "{\"scenario\":{}}",
                "{\"scenarioId\":\"" + MAIN + "\",\"scenario\":" + main + "}",
                "{\"scenarioId\":\"" + MAIN + "\",\"scenario\":null}",
                "{\"scenarioId\":\"" + MAIN + "\",\"unknown\":0}",
                "{\"scenarioId\":\"" + MAIN + "\",\"scenarioId\":\"disconnected\"}",
                selector(MAIN) + " {}", selector(MAIN) + " trailing", selector(MAIN) + " null");
        for (String path : SELECTED_POSTS) {
            for (String request : invalid) bad(path, request, 400);
            bad(path, "{\"scenario\":" + main.replace("\"socHostId\":\"soc\"",
                    "\"socHostId\":\"soc\",\"socHostId\":\"soc\"") + "}", 400);
        }
    }

    @Test
    void loadRejectsUnknownSelectorsAndEdgeCasesAcceptOnlyEmptyObject() throws Exception {
        login("admin");
        for (String body : List.of("", "null", "[]", "{}", "{\"id\":\"" + MAIN + "\"}",
                "{\"scenarioId\":null}", "{\"scenarioId\":13}", "{\"scenarioId\":\" \"}",
                "{\"scenarioId\":\"unknown\"}", "{\"scenario\":" + loadMain() + "}",
                "{\"scenarioId\":\"" + MAIN + "\",\"extra\":true}", selector(MAIN) + " {}",
                "{\"scenarioId\":\"" + MAIN + "\",\"scenarioId\":\"" + MAIN + "\"}")) {
            bad("scenarios/load", body, 400);
        }
        for (String body : List.of("", "null", "[]", "{} {}", "{\"unexpected\":true}", selector(MAIN),
                "{\"unknown\":1,\"unknown\":2}")) bad("edge-cases", body, 400);
    }

    @Test
    void customScenarioValidationRejectsRangesMissingSocEmptyHostsIdsCostsAndCoercions() throws Exception {
        login("admin");
        List<Consumer<ObjectNode>> invalid = List.of(
                s -> s.put("id", " "), s -> s.remove("name"), s -> s.putNull("socHostId"),
                s -> s.put("socHostId", "missing"), s -> s.putArray("hosts"), s -> s.putNull("hosts"),
                s -> host(s, 0).put("riskScore", 0), s -> host(s, 0).put("riskScore", 11),
                s -> host(s, 0).put("criticality", 0), s -> host(s, 0).put("criticality", 11),
                s -> host(s, 0).put("riskScore", "5"), s -> host(s, 0).put("criticality", 2.5),
                s -> host(s, 0).put("criticality", true), s -> host(s, 0).remove("riskScore"),
                s -> host(s, 1).put("id", "soc"), s -> host(s, 1).put("name", "SOC"),
                s -> host(s, 1).put("id", 5), s -> host(s, 0).put("unknown", 1),
                s -> edge(s, 0).put("id", " "), s -> edge(s, 1).put("id", "chain-01"),
                s -> edge(s, 0).put("sourceId", "missing"), s -> edge(s, 0).put("targetId", "soc"),
                s -> edge(s, 0).put("cost", 0), s -> edge(s, 0).put("cost", -1),
                s -> edge(s, 0).put("cost", "1"), s -> edge(s, 0).putNull("cost"),
                s -> edge(s, 0).remove("compromised"), s -> edge(s, 0).put("compromised", "false"),
                s -> edge(s, 0).put("compromised", 0), s -> s.put("extra", true),
                s -> ((ArrayNode) s.path("teams")).remove(0),
                s -> ((ObjectNode) s.path("teams").get(0)).put("capacity", 0),
                s -> ((ObjectNode) s.path("teams").get(0)).put("capacity", "6"),
                s -> ((ObjectNode) s.path("teams").get(1)).put("capacity", 6),
                s -> ((ObjectNode) s.path("teams").get(1)).put("id", "team-6"),
                s -> scenarioTask(s, 0).put("hostName", "missing"),
                s -> scenarioTask(s, 1).put("id", "task-host-01"),
                s -> scenarioTask(s, 0).put("effortHours", 0),
                s -> scenarioTask(s, 0).put("effortHours", "4"),
                s -> scenarioTask(s, 0).put("baseValue", -1),
                s -> scenarioTask(s, 0).put("baseValue", "100"),
                s -> scenarioTask(s, 0).put("daysUnpatched", -1),
                s -> scenarioTask(s, 0).put("daysUnpatched", "0"),
                s -> scenarioTask(s, 0).putNull("daysUnpatched"));
        ObjectNode source = loadMain().deepCopy();
        for (Consumer<ObjectNode> mutation : invalid) {
            ObjectNode scenario = source.deepCopy();
            mutation.accept(scenario);
            bad("pipeline", custom(scenario), 400);
        }
        bad("backbone", custom(source).replace("\"cost\":1.0", "\"cost\":1e400"), 400);
        bad("backbone", custom(source).replace("\"cost\":1.0", "\"cost\":NaN"), 400);
        bad("assign", custom(source).replace("\"effortHours\":4", "\"effortHours\":2147483648"), 400);
    }

    @Test
    void excessiveDpStatesAndValueOverflowReturnClearValidationErrors() throws Exception {
        login("admin");
        ObjectNode oversizedStates = loadMain().deepCopy();
        ((ObjectNode) oversizedStates.path("teams").get(0)).put("capacity", Integer.MAX_VALUE);
        bad("assign", custom(oversizedStates), 400);
        bad("compare", custom(oversizedStates), 400);
        ObjectNode overflow = loadMain().deepCopy();
        scenarioTask(overflow, 0).put("effortHours", 1).put("baseValue", Integer.MAX_VALUE);
        scenarioTask(overflow, 1).put("effortHours", 1).put("baseValue", Integer.MAX_VALUE);
        bad("assign", custom(overflow), 400);
        bad("pipeline", custom(overflow), 400);
    }

    @Test
    void everyPostRejectsUnsupportedContentTypeWithJsonError() throws Exception {
        login("admin");
        for (String path : POSTS) {
            for (String type : List.of("text/plain", "application/xml", "application/json-not-really")) {
                HttpResponse<String> response = send(http, PREFIX + path, bodyFor(path), type, csrf(cookies));
                assertError(response, 415);
            }
        }
        assertEquals(200, send(http, PREFIX + "pipeline", selector(MAIN), "application/json; charset=UTF-8",
                csrf(cookies)).statusCode());
    }

    @Test
    void edgeCaseRunnerReportsThreeSemanticChecksWithoutSpeedAssertions() throws Exception {
        login("admin");
        JsonNode response = ok("POST", "edge-cases", "{}");
        fields(response, "results", "allPassed");
        assertTrue(response.path("allPassed").asBoolean());
        assertEquals(Set.of("disconnected", "over-demand", "oversized-task"), ids(response.path("results")));
        for (JsonNode result : response.path("results")) {
            fields(result, "id", "name", "report", "passed", "expectation");
            assertTrue(result.path("passed").asBoolean());
            assertFalse(result.path("expectation").asText().isBlank());
            pipelineFields(result.path("report"));
        }
        JsonNode over = byId(response.path("results"), "over-demand").path("report").path("assignment");
        assertTrue(over.path("totalValue").asInt() > 0);
        assertTrue(over.path("unassigned").size() > 0);
        assertEquals("UNASSIGNED_CAPACITY", over.path("unassigned").get(0).path("reason").asText());
        JsonNode oversized = byId(response.path("results"), "oversized-task").path("report").path("assignment");
        assertEquals("OVERWEIGHT", oversized.path("excluded").get(0).path("reason").asText());
        assertEquals("task-oversized", oversized.path("excluded").get(0).path("task").path("id").asText());
        assertEquals(Set.of("task-fitting-a", "task-fitting-b"), assignedIds(oversized));
    }

    @Test
    void largeScenarioBackboneAndPipelineHandle200HostsAnd1000Links() throws Exception {
        login("admin");
        JsonNode backboneResponse = ok("POST", "backbone", selector(LARGE));
        backboneFields(backboneResponse.path("backbone"));
        assertEquals(200, backboneResponse.path("scenario").path("hosts").size());
        assertEquals(1000, backboneResponse.path("scenario").path("links").size());
        assertEquals(199, backboneResponse.path("backbone").path("chosenLinks").size());
        assertEquals(1000, backboneResponse.path("backbone").path("sortedCandidates").size());
        assertTrue(backboneResponse.path("backbone").path("connected").asBoolean());
        JsonNode pipeline = ok("POST", "pipeline", selector(LARGE));
        pipelineFields(pipeline);
        assertEquals(200, pipeline.path("activeHosts").size());
        assertEquals(1000, pipeline.path("activeLinks").size());
        assertEquals(0, pipeline.path("droppedTasks").size());
    }

    @Test
    void customSelectorsWorkOnEveryAlgorithmPathWithoutSharedLastScenario() throws Exception {
        login("admin");
        ObjectNode customScenario = loadMain().deepCopy();
        customScenario.put("id", "custom-request");
        customScenario.put("name", "Independent custom scenario");
        for (JsonNode task : customScenario.path("tasks")) ((ObjectNode) task).put("baseValue", 0);
        String original = customScenario.toString();
        for (String path : SELECTED_POSTS) {
            JsonNode response = ok("POST", path, custom(customScenario));
            JsonNode scenario = "sensitivity".equals(path) ? response.path("before").path("scenario")
                    : response.path("scenario");
            assertEquals("custom-request", scenario.path("id").asText(), path);
        }
        assertEquals(original, customScenario.toString());
        assertEquals(0, ok("POST", "pipeline", custom(customScenario)).path("assignment").path("totalValue").asInt());
        assertEquals(290, ok("POST", "pipeline", selector(MAIN)).path("assignment").path("totalValue").asInt());
        bad("pipeline", "{}", 400); // There is no implicit last-loaded scenario.
        HttpRequest mainRequest = postRequest(PREFIX + "pipeline", selector(MAIN), "application/json", csrf(cookies));
        HttpRequest customRequest = postRequest(PREFIX + "pipeline", custom(customScenario), "application/json", csrf(cookies));
        var first = http.sendAsync(mainRequest, HttpResponse.BodyHandlers.ofString());
        var second = http.sendAsync(customRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, first.get().statusCode());
        assertEquals(200, second.get().statusCode());
        assertEquals(290, mapper.readTree(first.get().body()).path("assignment").path("totalValue").asInt());
        assertEquals(0, mapper.readTree(second.get().body()).path("assignment").path("totalValue").asInt());
    }

    @Test
    void daaDoesNotMutateLiveNetworkSimulatorAlertsTeamsOrState() throws Exception {
        login("admin");
        JsonNode network = mapper.readTree(call("GET", "/api/network", null).body());
        JsonNode state = mapper.readTree(call("GET", "/api/state", null).body());
        JsonNode alerts = mapper.readTree(call("GET", "/api/alerts", null).body());
        Object simulator = facade.getTrafficSimulator();
        Object liveNetwork = facade.getNetwork();
        var lastPlanField = IncidentResponseFacade.class.getDeclaredField("lastResponsePlan");
        lastPlanField.setAccessible(true);
        Object lastPlan = lastPlanField.get(facade);
        for (String path : POSTS) assertEquals(200, call("POST", PREFIX + path, bodyFor(path)).statusCode(), path);
        assertEquals(200, call("GET", PREFIX + "scenarios", null).statusCode());
        assertEquals(network, mapper.readTree(call("GET", "/api/network", null).body()));
        assertEquals(state, mapper.readTree(call("GET", "/api/state", null).body()));
        assertEquals(alerts, mapper.readTree(call("GET", "/api/alerts", null).body()));
        assertSame(simulator, facade.getTrafficSimulator());
        assertSame(liveNetwork, facade.getNetwork());
        assertSame(lastPlan, lastPlanField.get(facade));
        assertFalse(facade.isRunning());
        assertFalse(facade.isAutoMode());
    }

    private void scenarioFields(JsonNode scenario) {
        fields(scenario, "id", "name", "socHostId", "hosts", "links", "teams", "tasks");
        for (JsonNode host : scenario.path("hosts")) fields(host, "id", "name", "riskScore", "criticality");
        for (JsonNode edge : scenario.path("links")) edgeFields(edge);
        for (JsonNode team : scenario.path("teams")) fields(team, "id", "capacity");
        for (JsonNode task : scenario.path("tasks")) scenarioTaskFields(task);
    }

    private void backboneFields(JsonNode backbone) {
        fields(backbone, "chosenLinks", "sortedCandidates", "totalWeight", "connected", "unreachable",
                "operations", "medianNanos", "warmupRuns", "measuredRuns");
        timing(backbone);
        fields(backbone.path("operations"), "comparisons", "findCalls", "unions", "edgesScanned");
        for (JsonNode counter : backbone.path("operations")) {
            assertTrue(counter.isIntegralNumber());
            assertTrue(counter.asLong() >= 0);
        }
        for (JsonNode edge : backbone.path("chosenLinks")) edgeFields(edge);
        for (JsonNode edge : backbone.path("sortedCandidates")) edgeFields(edge);
        for (JsonNode host : backbone.path("unreachable")) {
            fields(host, "id", "name");
            assertFalse(host.path("name").asText().isBlank());
        }
    }

    private void assignmentFields(JsonNode assignment, String solver, int teamCount) {
        fields(assignment, "solver", "totalValue", "stateCount", "transitionOps", "medianNanos", "warmupRuns",
                "measuredRuns", "teams", "excluded", "unassigned");
        assertEquals(solver, assignment.path("solver").asText());
        timing(assignment);
        assertEquals(teamCount, assignment.path("teams").size());
        Set<String> uniqueTasks = new HashSet<>();
        int totalValue = 0;
        for (JsonNode team : assignment.path("teams")) {
            fields(team, "id", "capacity", "usedHours", "tasks");
            int used = 0;
            for (JsonNode task : team.path("tasks")) {
                taskFields(task);
                assertTrue(uniqueTasks.add(task.path("id").asText()), "Task assigned twice");
                used += task.path("effortHours").asInt();
                totalValue += task.path("adjustedValue").asInt();
            }
            assertEquals(used, team.path("usedHours").asInt());
            assertTrue(used <= team.path("capacity").asInt());
        }
        assertEquals(totalValue, assignment.path("totalValue").asInt());
        for (String list : List.of("excluded", "unassigned")) {
            assertTrue(assignment.path(list).isArray());
            for (JsonNode row : assignment.path(list)) {
                fields(row, "task", "reason");
                taskFields(row.path("task"));
                assertFalse(row.path("reason").asText().isBlank());
                assertTrue(uniqueTasks.add(row.path("task").path("id").asText()), "Task dispositions overlap");
            }
        }
    }

    private void pipelineFields(JsonNode pipeline) {
        fields(pipeline, "scenario", "activeHosts", "activeLinks", "ageGrowthRate", "tasks", "backbone",
                "assignment", "droppedTasks");
        scenarioFields(pipeline.path("scenario"));
        backboneFields(pipeline.path("backbone"));
        assignmentFields(pipeline.path("assignment"), "DP", pipeline.path("scenario").path("teams").size());
        for (JsonNode host : pipeline.path("activeHosts")) fields(host, "id", "name", "riskScore", "criticality");
        for (JsonNode edge : pipeline.path("activeLinks")) edgeFields(edge);
        for (JsonNode task : pipeline.path("tasks")) taskFields(task);
        for (JsonNode row : pipeline.path("droppedTasks")) {
            fields(row, "task", "reason");
            scenarioTaskFields(row.path("task"));
            assertFalse(row.path("reason").asText().isBlank());
        }
    }

    private void timing(JsonNode measured) {
        assertTrue(measured.path("medianNanos").isIntegralNumber());
        assertTrue(measured.path("medianNanos").asLong() >= 0);
        assertEquals(1, measured.path("warmupRuns").asInt());
        assertEquals(5, measured.path("measuredRuns").asInt());
    }

    private void edgeFields(JsonNode edge) {
        fields(edge, "id", "sourceId", "targetId", "cost", "compromised");
        assertTrue(edge.path("compromised").isBoolean());
        assertTrue(edge.path("cost").isNumber());
        assertTrue(edge.path("cost").asDouble() > 0);
    }

    private void taskFields(JsonNode task) {
        fields(task, "id", "hostId", "hostName", "effortHours", "baseValue", "daysUnpatched", "adjustedValue");
    }

    private void scenarioTaskFields(JsonNode task) {
        fields(task, "id", "hostName", "effortHours", "baseValue", "daysUnpatched");
    }

    private void fields(JsonNode node, String... expected) {
        assertTrue(node.isObject(), node.toString());
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        assertEquals(Set.of(expected), actual, node.toString());
    }

    private Set<String> ids(JsonNode array) {
        assertTrue(array.isArray(), array.toString());
        Set<String> ids = new HashSet<>();
        for (JsonNode row : array) assertTrue(ids.add(row.path("id").asText()), "Duplicate ID");
        return ids;
    }

    private Set<String> strings(JsonNode array) {
        assertTrue(array.isArray());
        Set<String> result = new HashSet<>();
        for (JsonNode item : array) {
            assertTrue(item.isTextual());
            assertTrue(result.add(item.asText()), "Duplicate ID");
        }
        return result;
    }

    private Set<String> assignedIds(JsonNode assignment) {
        Set<String> ids = new HashSet<>();
        for (JsonNode team : assignment.path("teams")) ids.addAll(ids(team.path("tasks")));
        return ids;
    }

    private JsonNode byId(JsonNode rows, String id) {
        for (JsonNode row : rows) if (id.equals(row.path("id").asText())) return row;
        fail("Missing ID " + id);
        return null;
    }

    private ObjectNode host(ObjectNode scenario, int index) {
        return (ObjectNode) scenario.path("hosts").get(index);
    }

    private ObjectNode edge(ObjectNode scenario, int index) {
        return (ObjectNode) scenario.path("links").get(index);
    }

    private ObjectNode scenarioTask(ObjectNode scenario, int index) {
        return (ObjectNode) scenario.path("tasks").get(index);
    }

    private ObjectNode thirteenTasks() throws Exception {
        ObjectNode scenario = loadMain().deepCopy();
        ArrayNode tasks = scenario.putArray("tasks");
        for (int i = 0; i < 13; i++) {
            tasks.addObject().put("id", "custom-task-" + i).put("hostName", "Host 01")
                    .put("effortHours", 1).put("baseValue", i + 1).put("daysUnpatched", 0);
        }
        return scenario;
    }

    private JsonNode loadMain() throws Exception {
        return ok("POST", "scenarios/load", selector(MAIN));
    }

    private String custom(JsonNode scenario) {
        return mapper.createObjectNode().set("scenario", scenario).toString();
    }

    private static String selector(String id) {
        return "{\"scenarioId\":\"" + id + "\"}";
    }

    private static String bodyFor(String path) {
        return "edge-cases".equals(path) ? "{}" : selector(MAIN);
    }

    private void login(String user) throws Exception {
        HttpResponse<String> response = call("POST", "/api/login", credentials(user));
        assertEquals(200, response.statusCode(), response.body());
        String expected = switch (user) {
            case "admin" -> "Admin";
            case "analyst" -> "Analyst";
            default -> "Viewer";
        };
        assertEquals(expected, mapper.readTree(response.body()).path("role").asText());
    }

    private static String credentials(String user) {
        return "{\"username\":\"" + user + "\",\"password\":\"" + user + "\"}";
    }

    private JsonNode ok(String method, String path, String json) throws Exception {
        HttpResponse<String> response = call(method, PREFIX + path, json);
        assertEquals(200, response.statusCode(), path + ": " + response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        return mapper.readTree(response.body());
    }

    private void bad(String path, String body, int status) throws Exception {
        assertError(call("POST", PREFIX + path, body), status);
    }

    private void assertError(HttpResponse<String> response, int status) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        JsonNode error = mapper.readTree(response.body());
        fields(error, "error");
        assertTrue(error.path("error").isTextual());
        assertFalse(error.path("error").asText().isBlank());
    }

    private HttpResponse<String> call(String method, String path, String json) throws Exception {
        if ("POST".equals(method)) return send(http, path, json, "application/json", csrf(cookies));
        HttpRequest request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(HttpClient client, String path, String json, String type, String token)
            throws Exception {
        return client.send(postRequest(path, json, type, token), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest postRequest(String path, String json, String type, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30));
        if (type != null) builder.header("Content-Type", type);
        if (token != null) builder.header("X-CSRF-Token", token);
        return builder.POST(HttpRequest.BodyPublishers.ofString(json == null ? "" : json)).build();
    }

    private static String csrf(CookieManager manager) {
        return manager.getCookieStore().getCookies().stream().filter(cookie -> "csrf".equals(cookie.getName()))
                .map(HttpCookie::getValue).findFirst().orElse(null);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
