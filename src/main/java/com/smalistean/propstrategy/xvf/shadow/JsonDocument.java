package com.smalistean.propstrategy.xvf.shadow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.Objects;

/**
 * Validated immutable JSON text for PostgreSQL JSONB columns.
 *
 * <p>The shadow schema deliberately keeps venue-specific payloads flexible while its decision-
 * critical values remain typed columns. Keeping JSON as a {@link String}, rather than exposing a
 * mutable Jackson tree, makes the Java audit record genuinely immutable as well.
 */
public record JsonDocument(String json) {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public JsonDocument {
        Objects.requireNonNull(json, "json");
        parse(json);
    }

    public static JsonDocument object(String json) {
        JsonDocument document = new JsonDocument(json);
        if (!document.isObject()) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return document;
    }

    public static JsonDocument array(String json) {
        JsonDocument document = new JsonDocument(json);
        if (!document.isArray()) {
            throw new IllegalArgumentException("Expected a JSON array");
        }
        return document;
    }

    public static JsonDocument emptyObject() {
        return object("{}");
    }

    public static JsonDocument emptyArray() {
        return array("[]");
    }

    public boolean isObject() {
        return parse(json).isObject();
    }

    public boolean isArray() {
        return parse(json).isArray();
    }

    public int size() {
        return parse(json).size();
    }

    private static JsonNode parse(String json) {
        try {
            JsonNode parsed = MAPPER.readTree(json);
            if (parsed == null || parsed.isNull()) {
                throw new IllegalArgumentException("JSON null is not an audit snapshot");
            }
            return parsed;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON audit snapshot", e);
        }
    }
}
