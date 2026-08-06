package com.smalistean.propstrategy.marketdownloader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smalistean.propstrategy.database.Kline;

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

public class BinanceKlineClient {

    private static final String BASE_URL = "https://fapi.binance.com/fapi/v1/klines";
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration MIN_REQUEST_INTERVAL = Duration.ofMillis(160);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final Duration minimumRequestInterval;
    private long lastRequestNanos;

    public BinanceKlineClient() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build(), new ObjectMapper(), BASE_URL, MIN_REQUEST_INTERVAL);
    }

    public BinanceKlineClient(HttpClient httpClient) {
        this(httpClient, new ObjectMapper(), BASE_URL, MIN_REQUEST_INTERVAL);
    }

    public BinanceKlineClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this(httpClient, objectMapper, BASE_URL, MIN_REQUEST_INTERVAL);
    }

    BinanceKlineClient(HttpClient httpClient, ObjectMapper objectMapper,
                       String baseUrl, Duration minimumRequestInterval) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.minimumRequestInterval = minimumRequestInterval;
    }

    public List<Kline> fetchKlines(String symbol, String interval, long startTime, long endTime, int limit) {
        validateRequest(symbol, interval, startTime, endTime, limit);
        String url = "%s?symbol=%s&interval=%s&startTime=%d&endTime=%d&limit=%d"
                .formatted(baseUrl, symbol, interval, startTime, endTime, limit);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                paceRequests();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return parseKlines(response.body());
                }
                if (!isRetryable(response.statusCode()) || attempt == MAX_ATTEMPTS) {
                    throw new IllegalStateException("Binance API error: HTTP %d - %s"
                            .formatted(response.statusCode(), response.body()));
                }
                sleep(retryDelay(response, attempt));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while fetching klines for " + symbol, e);
            } catch (IOException e) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new IllegalStateException("Failed to fetch klines for " + symbol, e);
                }
                sleep(backoff(attempt));
            }
        }
        throw new IllegalStateException("Failed to fetch klines for " + symbol);
    }

    List<Kline> parseKlines(String json) throws IOException {
        JsonNode rows = objectMapper.readTree(json);
        if (!rows.isArray()) {
            throw new IOException("Expected Binance kline response to be an array");
        }
        for (JsonNode row : rows) {
            if (!row.isArray() || row.size() < 11) {
                throw new IOException("Binance returned an invalid kline row");
            }
        }

        return StreamSupport.stream(rows.spliterator(), false)
                .map(row -> new Kline(
                        Instant.ofEpochMilli(row.get(0).asLong()),
                        new BigDecimal(row.get(1).asText()),
                        new BigDecimal(row.get(2).asText()),
                        new BigDecimal(row.get(3).asText()),
                        new BigDecimal(row.get(4).asText()),
                        new BigDecimal(row.get(5).asText()),
                        Instant.ofEpochMilli(row.get(6).asLong()),
                        new BigDecimal(row.get(7).asText()),
                        row.get(8).asInt(),
                        new BigDecimal(row.get(9).asText()),
                        new BigDecimal(row.get(10).asText())
                ))
                .toList();
    }

    private static void validateRequest(String symbol, String interval,
                                        long startTime, long endTime, int limit) {
        if (symbol == null || !symbol.matches("[A-Z0-9]{2,20}")) {
            throw new IllegalArgumentException("Invalid Binance symbol: " + symbol);
        }
        KlineInterval.fromCode(interval);
        if (startTime < 0 || endTime < startTime) {
            throw new IllegalArgumentException("Invalid kline time range");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("Kline limit must be between 1 and 1000");
        }
    }

    private synchronized void paceRequests() throws InterruptedException {
        long waitNanos = minimumRequestInterval.toNanos()
                - (System.nanoTime() - lastRequestNanos);
        if (waitNanos > 0) {
            long millis = waitNanos / 1_000_000;
            int nanos = (int) (waitNanos % 1_000_000);
            Thread.sleep(millis, nanos);
        }
        lastRequestNanos = System.nanoTime();
    }

    private static boolean isRetryable(int statusCode) {
        return statusCode == 418 || statusCode == 429 || statusCode >= 500;
    }

    private static Duration retryDelay(HttpResponse<?> response, int attempt) {
        return response.headers().firstValue("Retry-After")
                .flatMap(BinanceKlineClient::parseSeconds)
                .orElseGet(() -> backoff(attempt));
    }

    private static java.util.Optional<Duration> parseSeconds(String value) {
        try {
            return java.util.Optional.of(Duration.ofSeconds(Long.parseLong(value)));
        } catch (NumberFormatException ignored) {
            return java.util.Optional.empty();
        }
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
