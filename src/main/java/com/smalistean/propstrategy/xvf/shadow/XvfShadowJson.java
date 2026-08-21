package com.smalistean.propstrategy.xvf.shadow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Canonical JSON and hashing used by the shadow audit writer. */
final class XvfShadowJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private XvfShadowJson() {
    }

    static JsonDocument object(Object value) {
        JsonDocument document = new JsonDocument(write(value));
        if (!document.isObject()) {
            throw new IllegalArgumentException("Expected an object-shaped shadow snapshot");
        }
        return document;
    }

    static JsonDocument array(Object value) {
        JsonDocument document = new JsonDocument(write(value));
        if (!document.isArray()) {
            throw new IllegalArgumentException("Expected an array-shaped shadow snapshot");
        }
        return document;
    }

    static String sha256(JsonDocument document) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(document.json().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize shadow audit snapshot", e);
        }
    }
}
