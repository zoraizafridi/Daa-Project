package pk.ooad.ntd.scenario;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Strict scenario input reader; bundled resources are selected only by an allowlisted ID. */
public final class ScenarioLoader {
    private static final List<String> IDS = List.of(
            "main-12-hosts-22-links", "disconnected", "over-demand", "oversized-task", ScenarioGenerator.LARGE_ID);
    private static final ObjectMapper MAPPER = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .withCoercionConfig(LogicalType.Textual, config -> config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .build();

    private ScenarioLoader() {
    }

    public static List<String> availableIds() {
        return IDS;
    }

    public static Scenario load(String id) {
        if (id == null || !IDS.contains(id)) {
            throw new IllegalArgumentException("scenario id must be one of " + IDS + "; received: " + id);
        }
        if (ScenarioGenerator.LARGE_ID.equals(id)) {
            return ScenarioGenerator.large(42L);
        }
        String resource = "/scenarios/" + id + ".json";
        try (InputStream input = ScenarioLoader.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalArgumentException("scenario resource is missing: " + resource);
            }
            Scenario scenario = read(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            if (!id.equals(scenario.id())) {
                throw new IllegalArgumentException("scenario resource id must match " + id
                        + "; received: " + scenario.id());
            }
            return scenario;
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to read scenario resource " + resource + ": "
                    + e.getMessage(), e);
        }
    }

    public static Scenario read(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("scenario JSON must not be null or blank");
        }
        try {
            Scenario scenario = MAPPER.readValue(json, Scenario.class);
            if (scenario == null) {
                throw new IllegalArgumentException("scenario JSON root must be an object, not null");
            }
            return scenario;
        } catch (JsonProcessingException e) {
            String path = e instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()
                    ? " at " + mapping.getPathReference() : "";
            throw new IllegalArgumentException("Invalid scenario JSON" + path + ": " + e.getOriginalMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid scenario JSON: " + e.getMessage(), e);
        }
    }
}
