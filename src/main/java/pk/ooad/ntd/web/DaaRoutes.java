package pk.ooad.ntd.web;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import pk.ooad.ntd.scenario.Scenario;
import pk.ooad.ntd.scenario.ScenarioLoader;
import pk.ooad.ntd.service.AssignmentService;
import pk.ooad.ntd.service.BackboneService;
import pk.ooad.ntd.service.ResponseCoordinator;
import pk.ooad.ntd.service.SensitivityService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class DaaRoutes {
    private static final ObjectMapper REQUEST_JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> SELECTORS = Set.of("scenarioId", "scenario");
    private static final Set<String> REPAIR_FIELDS = Set.of("scenarioId", "scenario", "linkIds");

    public void register(Javalin app) {
        // DAA privileges require the session role, not the broader live-planner permission.
        app.before("/api/daa/*", ctx -> {
            if (ctx.sessionAttribute("username") == null) {
                ctx.status(401).result("");
                ctx.skipRemainingHandlers();
            } else if (!"Admin".equals(ctx.sessionAttribute("role"))) {
                ctx.status(403).result("");
                ctx.skipRemainingHandlers();
            }
        });
        app.get("/api/daa/scenarios", ctx -> ctx.json(ScenarioLoader.availableIds().stream()
                .map(ScenarioLoader::load).map(DaaDtos::summary).toList()));
        app.post("/api/daa/scenarios/load", ctx -> post(ctx, body -> {
            rejectUnknown(body, Set.of("scenarioId"));
            return ScenarioLoader.load(requiredText(body.get("scenarioId"), "scenarioId"));
        }));
        app.post("/api/daa/backbone", ctx -> post(ctx, body -> {
            Scenario scenario = select(body, SELECTORS);
            return new DaaDtos.BackboneResponse(scenario, DaaDtos.backbone(new BackboneService().plan(scenario)));
        }));
        app.post("/api/daa/backbone/repair", ctx -> post(ctx, body -> {
            Scenario scenario = select(body, REPAIR_FIELDS);
            var report = new BackboneService().repair(scenario, repairLinkIds(body));
            return new DaaDtos.RepairResponse(report.reducedScenario(), report.compromisedLinkIds(),
                    DaaDtos.backbone(report.original()), DaaDtos.backbone(report.incremental()),
                    DaaDtos.backbone(report.fullRecompute()), report.sameEdges(), report.sameCost());
        }));
        app.post("/api/daa/assign", ctx -> post(ctx, body -> {
            Scenario scenario = select(body, SELECTORS);
            var report = new AssignmentService().assign(scenario);
            return new DaaDtos.AssignmentResponse(scenario, report.ageGrowthRate(),
                    DaaDtos.tasks(scenario, report.tasks()), DaaDtos.assignment(scenario, "DP", report.assignment()));
        }));
        app.post("/api/daa/compare", ctx -> post(ctx, body -> {
            Scenario scenario = select(body, SELECTORS);
            var report = new AssignmentService().compare(scenario);
            return new DaaDtos.ComparisonResponse(scenario, report.ageGrowthRate(),
                    DaaDtos.tasks(scenario, report.tasks()), DaaDtos.assignment(scenario, "DP", report.dp()),
                    DaaDtos.assignment(scenario, "GREEDY", report.greedy()), report.bruteForce() == null ? null
                    : DaaDtos.assignment(scenario, "BRUTE_FORCE", report.bruteForce()), report.bruteForceError());
        }));
        app.post("/api/daa/pipeline", ctx -> post(ctx, body ->
                DaaDtos.pipeline(new ResponseCoordinator().run(select(body, SELECTORS)))));
        app.post("/api/daa/sensitivity", ctx -> post(ctx, body ->
                DaaDtos.sensitivity(new SensitivityService().analyze(select(body, SELECTORS)))));
        app.post("/api/daa/edge-cases", ctx -> post(ctx, body -> {
            rejectUnknown(body, Set.of());
            return edgeCases();
        }));
    }

    private static void post(Context ctx, Function<JsonNode, Object> action) {
        String type = ctx.contentType();
        if (type != null && !type.isBlank()
                && !"application/json".equals(type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT))) {
            ctx.status(415).json(Map.of("error", "Content-Type must be application/json"));
            return;
        }
        try {
            ctx.json(action.apply(requestJson(ctx.body())));
        } catch (IllegalArgumentException ex) {
            String message = ex.getMessage();
            ctx.status(400).json(Map.of("error", message == null || message.isBlank()
                    ? "Invalid DAA request" : message));
        } catch (ArithmeticException ex) {
            ctx.status(400).json(Map.of("error", "Scenario exceeds supported numeric limits"));
        }
    }

    private static JsonNode requestJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Request body must be a nonblank JSON object");
        }
        try {
            JsonNode body = REQUEST_JSON.readTree(raw);
            if (body == null || !body.isObject()) {
                throw new IllegalArgumentException("Request JSON root must be an object");
            }
            return body;
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid request JSON: " + ex.getOriginalMessage(), ex);
        }
    }

    private static Scenario select(JsonNode body, Set<String> fields) {
        rejectUnknown(body, fields);
        boolean id = body.has("scenarioId");
        boolean custom = body.has("scenario");
        if (id == custom) {
            throw new IllegalArgumentException("Provide exactly one selector: scenarioId or scenario");
        }
        if (id) {
            return ScenarioLoader.load(requiredText(body.get("scenarioId"), "scenarioId"));
        }
        JsonNode nested = body.get("scenario");
        if (!nested.isObject()) {
            throw new IllegalArgumentException("scenario must be a Scenario JSON object");
        }
        return ScenarioLoader.read(nested.toString());
    }

    private static void rejectUnknown(JsonNode body, Set<String> allowed) {
        body.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException("Unknown request field: " + field);
            }
        });
    }

    private static String requiredText(JsonNode node, String field) {
        if (node == null || !node.isTextual() || node.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " must be a nonblank string");
        }
        return node.textValue();
    }

    private static List<String> repairLinkIds(JsonNode body) {
        if (!body.has("linkIds")) {
            return null;
        }
        JsonNode ids = body.get("linkIds");
        if (!ids.isArray() || ids.size() != 3) {
            throw new IllegalArgumentException("linkIds must be an array of exactly 3 distinct tree link IDs");
        }
        List<String> links = new ArrayList<>();
        for (JsonNode id : ids) {
            links.add(requiredText(id, "linkIds entry"));
        }
        if (new HashSet<>(links).size() != 3) {
            throw new IllegalArgumentException("linkIds must contain exactly 3 distinct tree link IDs");
        }
        return List.copyOf(links);
    }

    private static DaaDtos.EdgeCasesResponse edgeCases() {
        List<DaaDtos.EdgeCaseDto> results = new ArrayList<>();
        for (String id : List.of("disconnected", "over-demand", "oversized-task")) {
            Scenario scenario = ScenarioLoader.load(id);
            DaaDtos.PipelineDto report = DaaDtos.pipeline(new ResponseCoordinator().run(scenario));
            Set<String> assigned = report.assignment().teams().stream().flatMap(team -> team.tasks().stream())
                    .map(DaaDtos.AdjustedTaskDto::id).collect(Collectors.toSet());
            boolean passed;
            String expectation;
            switch (id) {
                case "disconnected" -> {
                    Set<String> unreachableNames = report.backbone().unreachable().stream()
                            .map(DaaDtos.HostRef::name).collect(Collectors.toSet());
                    Set<String> outsideTasks = scenario.tasks().stream()
                            .filter(task -> unreachableNames.contains(task.hostName())).map(Scenario.Task::id)
                            .collect(Collectors.toSet());
                    Set<String> dropped = report.droppedTasks().stream()
                            .filter(row -> "OUTSIDE_SOC_COMPONENT".equals(row.reason()))
                            .map(row -> row.task().id()).collect(Collectors.toSet());
                    passed = !report.backbone().connected() && !outsideTasks.isEmpty()
                            && dropped.containsAll(outsideTasks) && outsideTasks.stream().noneMatch(assigned::contains);
                    expectation = "Disconnected backbone; all tasks outside the SOC component are dropped, not assigned";
                }
                case "over-demand" -> {
                    passed = report.assignment().totalValue() > 0 && !assigned.isEmpty()
                            && report.assignment().unassigned().stream()
                            .anyMatch(row -> "UNASSIGNED_CAPACITY".equals(row.reason()));
                    expectation = "Positive-value work is assigned and excess demand is unassigned due to capacity";
                }
                case "oversized-task" -> {
                    int largest = scenario.teams().stream().mapToInt(Scenario.Team::capacity).max().orElseThrow();
                    Set<String> oversized = scenario.tasks().stream().filter(task -> task.effortHours() > largest)
                            .map(Scenario.Task::id).collect(Collectors.toSet());
                    Set<String> excluded = report.assignment().excluded().stream()
                            .filter(row -> "OVERWEIGHT".equals(row.reason())).map(row -> row.task().id())
                            .collect(Collectors.toSet());
                    passed = !oversized.isEmpty() && excluded.containsAll(oversized)
                            && oversized.stream().noneMatch(assigned::contains)
                            && scenario.tasks().stream().filter(task -> task.effortHours() <= largest)
                            .allMatch(task -> assigned.contains(task.id()));
                    expectation = "Oversized tasks are excluded as OVERWEIGHT while fitting tasks are assigned";
                }
                default -> throw new IllegalStateException("Unknown edge-case fixture: " + id);
            }
            results.add(new DaaDtos.EdgeCaseDto(id, scenario.name(), report, passed, expectation));
        }
        return new DaaDtos.EdgeCasesResponse(List.copyOf(results),
                results.stream().allMatch(DaaDtos.EdgeCaseDto::passed));
    }
}
