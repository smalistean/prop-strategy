package com.smalistean.propstrategy.marketdownloader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smalistean.propstrategy.database.FundingRate;

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

public final class BinanceFundingRateClient {

    private static final String BASE_URL = "https://fapi.binance.com/fapi/v1/fundingRate";
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration MIN_REQUEST_INTERVAL = Duration.ofMillis(650);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final Duration minimumRequestInterval;
    private long lastRequestNanos;

    public BinanceFundingRateClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                new ObjectMapper(), BASE_URL, MIN_REQUEST_INTERVAL);
    }

    BinanceFundingRateClient(HttpClient httpClient, ObjectMapper objectMapper,
                             String baseUrl, Duration minimumRequestInterval) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.minimumRequestInterval = minimumRequestInterval;
    }

    public List<FundingRate> fetch(String symbol, long startTime, long endTime, int limit) {
        validateRequest(symbol, startTime, endTime, limit);
        String url = "%s?symbol=%s&startTime=%d&endTime=%d&limit=%d"
                .formatted(baseUrl, symbol, startTime, endTime, limit);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                paceRequests();
                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return parse(response.body());
                }
                if (!isRetryable(response.statusCode()) || attempt == MAX_ATTEMPTS) {
                    throw new IllegalStateException("Binance API error: HTTP %d - %s"
                            .formatted(response.statusCode(), response.body()));
                }
                sleep(backoff(attempt));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while fetching funding rates", e);
            } catch (IOException e) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new IllegalStateException("Failed to fetch funding rates", e);
                }
                sleep(backoff(attempt));
            }
        }
        throw new IllegalStateException("Failed to fetch funding rates");
    }

    List<FundingRate> parse(String json) throws IOException {
        JsonNode rows = objectMapper.readTree(json);
        if (!rows.isArray()) {
            throw new IOException("Expected Binance funding-rate response to be an array");
        }
        for (JsonNode row : rows) {
            if (!row.hasNonNull("symbol") || !row.hasNonNull("fundingTime")
                    || !row.hasNonNull("fundingRate") || !row.hasNonNull("markPrice")) {
                throw new IOException("Binance returned an invalid funding-rate row");
            }
        }
        return StreamSupport.stream(rows.spliterator(), false)
                .map(row -> new FundingRate(
                        row.get("symbol").asText(),
                        Instant.ofEpochMilli(row.get("fundingTime").asLong()),
                        row.hasNonNull("rateType") ? row.get("rateType").asText() : "Regular",
                        new BigDecimal(row.get("fundingRate").asText()),
                        decimalOrNull(row.get("markPrice"))))
                .toList();
    }

    private static BigDecimal decimalOrNull(JsonNode value) {
        return value == null || value.asText().isBlank()
                ? null
                : new BigDecimal(value.asText());
    }

    private static void validateRequest(String symbol, long startTime, long endTime, int limit) {
        if (symbol == null || !symbol.matches("[A-Z0-9]{2,20}")) {
            throw new IllegalArgumentException("Invalid Binance symbol: " + symbol);
        }
        if (startTime < 0 || endTime < startTime) {
            throw new IllegalArgumentException("Invalid funding-rate time range");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("Funding-rate limit must be between 1 and 1000");
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
