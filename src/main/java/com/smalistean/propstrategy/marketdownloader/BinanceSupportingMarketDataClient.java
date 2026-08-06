package com.smalistean.propstrategy.marketdownloader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smalistean.propstrategy.database.OpenInterestStatistic;
import com.smalistean.propstrategy.database.TraderRatio;
import com.smalistean.propstrategy.database.TraderRatio.RatioType;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.StreamSupport;

public final class BinanceSupportingMarketDataClient {

    private static final String BASE_URL = "https://fapi.binance.com";
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration MIN_REQUEST_INTERVAL = Duration.ofMillis(350);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final Duration minimumRequestInterval;
    private final String apiKey;
    private long lastRequestNanos;

    public BinanceSupportingMarketDataClient(String apiKey) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                new ObjectMapper(), BASE_URL, MIN_REQUEST_INTERVAL, apiKey);
    }

    BinanceSupportingMarketDataClient(HttpClient httpClient, ObjectMapper objectMapper,
                                      String baseUrl, Duration minimumRequestInterval,
                                      String apiKey) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.minimumRequestInterval = minimumRequestInterval;
        this.apiKey = apiKey;
    }

    public List<OpenInterestStatistic> fetchOpenInterest(
            String symbol, String period, long startTime, long endTime, int limit) {
        String body = fetch("/futures/data/openInterestHist", symbol, period,
                startTime, endTime, limit, false);
        return parseOpenInterest(body, period);
    }

    public List<TraderRatio> fetchRatios(String symbol, String period, RatioType type,
                                         long startTime, long endTime, int limit) {
        String path = switch (type) {
            case GLOBAL_ACCOUNT -> "/futures/data/globalLongShortAccountRatio";
            case TOP_ACCOUNT -> "/futures/data/topLongShortAccountRatio";
            case TOP_POSITION -> "/futures/data/topLongShortPositionRatio";
        };
        String body = fetch(path, symbol, period, startTime, endTime, limit,
                type != RatioType.GLOBAL_ACCOUNT);
        return parseRatios(body, period, type);
    }

    List<OpenInterestStatistic> parseOpenInterest(String json, String period) {
        JsonNode rows = readArray(json, "open-interest");
        return StreamSupport.stream(rows.spliterator(), false)
                .map(row -> new OpenInterestStatistic(
                        requiredText(row, "symbol"),
                        period,
                        Instant.ofEpochMilli(requiredLong(row, "timestamp")),
                        requiredDecimal(row, "sumOpenInterest"),
                        requiredDecimal(row, "sumOpenInterestValue"),
                        optionalDecimal(row, "CMCCirculatingSupply")))
                .toList();
    }

    List<TraderRatio> parseRatios(String json, String period, RatioType type) {
        JsonNode rows = readArray(json, "trader-ratio");
        return StreamSupport.stream(rows.spliterator(), false)
                .map(row -> new TraderRatio(
                        requiredText(row, "symbol"),
                        period,
                        type,
                        Instant.ofEpochMilli(requiredLong(row, "timestamp")),
                        requiredDecimal(row, "longShortRatio"),
                        requiredDecimal(row, "longAccount"),
                        requiredDecimal(row, "shortAccount")))
                .toList();
    }

    private String fetch(String path, String symbol, String period,
                         long startTime, long endTime, int limit, boolean requiresApiKey) {
        validateRequest(symbol, period, startTime, endTime, limit);
        if (requiresApiKey && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalStateException("BINANCE_API_KEY is required for " + path);
        }
        String url = "%s%s?symbol=%s&period=%s&startTime=%d&endTime=%d&limit=%d"
                .formatted(baseUrl, path, symbol, period, startTime, endTime, limit);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .GET();
        if (requiresApiKey) {
            requestBuilder.header("X-MBX-APIKEY", apiKey);
        }
        HttpRequest request = requestBuilder.build();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                paceRequests();
                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return response.body();
                }
                if (!isRetryable(response.statusCode()) || attempt == MAX_ATTEMPTS) {
                    throw new IllegalStateException("Binance API error: HTTP %d - %s"
                            .formatted(response.statusCode(), response.body()));
                }
                sleep(backoff(attempt));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while fetching supporting data", e);
            } catch (IOException e) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new IllegalStateException("Failed to fetch supporting market data", e);
                }
                sleep(backoff(attempt));
            }
        }
        throw new IllegalStateException("Failed to fetch supporting market data");
    }

    private JsonNode readArray(String json, String dataName) {
        try {
            JsonNode rows = objectMapper.readTree(json);
            if (!rows.isArray()) {
                throw new IllegalArgumentException("Expected Binance " + dataName
                        + " response to be an array");
            }
            return rows;
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid Binance " + dataName + " JSON", e);
        }
    }

    private static String requiredText(JsonNode row, String field) {
        if (!row.hasNonNull(field) || row.get(field).asText().isBlank()) {
            throw new IllegalArgumentException("Missing Binance response field: " + field);
        }
        return row.get(field).asText();
    }

    private static long requiredLong(JsonNode row, String field) {
        requiredText(row, field);
        return row.get(field).asLong();
    }

    private static BigDecimal requiredDecimal(JsonNode row, String field) {
        return new BigDecimal(requiredText(row, field));
    }

    private static BigDecimal optionalDecimal(JsonNode row, String field) {
        return row.hasNonNull(field) && !row.get(field).asText().isBlank()
                ? new BigDecimal(row.get(field).asText())
                : null;
    }

    private static void validateRequest(String symbol, String period,
                                        long startTime, long endTime, int limit) {
        if (symbol == null || !symbol.matches("[A-Z0-9]{2,20}")) {
            throw new IllegalArgumentException("Invalid Binance symbol: " + symbol);
        }
        if (!List.of("5m", "15m", "30m", "1h", "2h", "4h", "6h", "12h", "1d")
                .contains(period)) {
            throw new IllegalArgumentException("Invalid supporting-data period: " + period);
        }
        if (startTime < 0 || endTime < startTime) {
            throw new IllegalArgumentException("Invalid supporting-data time range");
        }
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("Supporting-data limit must be between 1 and 500");
        }
    }

    private synchronized void paceRequests() throws InterruptedException {
        long waitNanos = minimumRequestInterval.toNanos()
                - (System.nanoTime() - lastRequestNanos);
        if (waitNanos > 0) {
            Thread.sleep(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
        }
        lastRequestNanos = System.nanoTime();
    }

    private static boolean isRetryable(int statusCode) {
        return statusCode == 418 || statusCode == 429 || statusCode >= 500;
    }

    private static Duration backoff(int attempt) {
        long baseMillis = 500L * (1L << Math.min(attempt - 1, 4));
        return Duration.ofMillis(baseMillis + ThreadLocalRandom.current().nextLong(250));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during Binance retry backoff", e);
        }
    }
}
