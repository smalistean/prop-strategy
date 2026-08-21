package com.smalistean.propstrategy.xvf.shadow;

import com.fasterxml.jackson.databind.JsonNode;
import com.smalistean.propstrategy.xvf.XvfConfig;
import com.smalistean.propstrategy.xvf.shadow.XvfPublicJsonTransport.TimedJson;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.BookLevel;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.ResponseTiming;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

final class XvfSnapshotParsing {

    private XvfSnapshotParsing() {
    }

    static Set<String> symbols(Set<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("venueSymbols must not be empty");
        }
        TreeSet<String> out = new TreeSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("venueSymbols must not contain blank values");
            }
            out.add(value);
        }
        return Set.copyOf(out);
    }

    static JsonNode requireArray(JsonNode node, String what) {
        if (node == null || !node.isArray()) {
            throw schema(what + " must be a JSON array");
        }
        return node;
    }

    static JsonNode requireObject(JsonNode node, String what) {
        if (node == null || !node.isObject()) {
            throw schema(what + " must be a JSON object");
        }
        return node;
    }

    static String text(JsonNode node, String field, String what) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || !value.isValueNode() || value.asText().isBlank()) {
            throw schema(what + "." + field + " must be present and non-blank");
        }
        return value.asText();
    }

    static Optional<String> optionalText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.asText());
    }

    static BigDecimal decimal(JsonNode node, String field, String what) {
        return optionalDecimal(node, field)
                .orElseThrow(() -> schema(what + "." + field + " must be a decimal"));
    }

    static Optional<BigDecimal> optionalDecimal(JsonNode node, String field) {
        return optionalText(node, field).map(value -> {
            try {
                return new BigDecimal(value);
            } catch (NumberFormatException e) {
                throw schema(field + " is not a decimal: " + value, e);
            }
        });
    }

    static Optional<Integer> optionalPositiveInteger(JsonNode node, String field) {
        Optional<String> raw = optionalText(node, field);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            int value = new BigDecimal(raw.get()).intValueExact();
            if (value <= 0) {
                throw schema(field + " must be positive");
            }
            return Optional.of(value);
        } catch (ArithmeticException | NumberFormatException e) {
            throw schema(field + " is not a positive integer: " + raw.get(), e);
        }
    }

    static Optional<Instant> optionalEpochMillis(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return Optional.empty();
        }
        try {
            long millis = value.isNumber() ? value.longValue() : Long.parseLong(value.asText());
            return millis > 0 ? Optional.of(Instant.ofEpochMilli(millis)) : Optional.empty();
        } catch (NumberFormatException e) {
            throw schema(field + " is not epoch milliseconds", e);
        }
    }

    static ResponseTiming timing(TimedJson response, Optional<Instant> sourceAt) {
        return new ResponseTiming(response.requestedAt(), sourceAt, response.receivedAt());
    }

    static List<BookLevel> priceLevels(JsonNode levels, String what, boolean withOrderCount) {
        requireArray(levels, what);
        List<BookLevel> out = new ArrayList<>();
        for (int index = 0; index < levels.size(); index++) {
            JsonNode level = levels.get(index);
            if (level.isArray()) {
                if (level.size() < 2) {
                    throw schema(what + "[" + index + "] must contain price and quantity");
                }
                out.add(new BookLevel(parseDecimal(level.get(0), what + " price"),
                        parseDecimal(level.get(1), what + " quantity"), Optional.empty()));
            } else if (level.isObject()) {
                Optional<Integer> count = withOrderCount
                        ? optionalPositiveInteger(level, "n") : Optional.empty();
                out.add(new BookLevel(decimal(level, "px", what), decimal(level, "sz", what), count));
            } else {
                throw schema(what + "[" + index + "] must be an array or object");
            }
        }
        return List.copyOf(out);
    }

    static BigDecimal baseUnitsPerContract(String venue, String venueSymbol) {
        String raw = "hyperliquid".equals(venue) ? venueSymbol : stripQuote(venueSymbol);
        for (String prefix : new String[] {"1000000", "100000", "10000", "1000"}) {
            if (raw.startsWith(prefix) && raw.length() > prefix.length()
                    && Character.isUpperCase(raw.charAt(prefix.length()))) {
                return new BigDecimal(prefix);
            }
        }
        if (raw.startsWith("1M") && raw.length() > 2 && Character.isUpperCase(raw.charAt(2))) {
            return new BigDecimal("1000000");
        }
        if (raw.startsWith("k") && raw.length() > 1 && Character.isUpperCase(raw.charAt(1))) {
            return new BigDecimal("1000");
        }
        return BigDecimal.ONE;
    }

    static String canonicalBase(String venue, String venueSymbol) {
        return XvfConfig.normaliseBase(venue, venueSymbol);
    }

    static URI uri(URI baseUri, String pathAndQuery) {
        String base = baseUri.toString();
        return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
                + (pathAndQuery.startsWith("/") ? pathAndQuery : "/" + pathAndQuery));
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static IllegalStateException schema(String message) {
        return new IllegalStateException("XVF public market schema error: " + message);
    }

    static IllegalStateException schema(String message, Throwable cause) {
        return new IllegalStateException("XVF public market schema error: " + message, cause);
    }

    private static BigDecimal parseDecimal(JsonNode node, String what) {
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw schema(what + " must be a decimal");
        }
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException e) {
            throw schema(what + " is not a decimal", e);
        }
    }

    private static String stripQuote(String symbol) {
        return symbol.endsWith("USDT") || symbol.endsWith("USDC")
                ? symbol.substring(0, symbol.length() - 4) : symbol;
    }
}
