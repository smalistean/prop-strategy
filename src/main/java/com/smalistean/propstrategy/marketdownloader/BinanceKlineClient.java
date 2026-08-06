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
import java.util.stream.StreamSupport;

public class BinanceKlineClient {

    private static final String BASE_URL = "https://fapi.binance.com/fapi/v1/klines";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public BinanceKlineClient() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build(), new ObjectMapper());
    }

    public BinanceKlineClient(HttpClient httpClient) {
        this(httpClient, new ObjectMapper());
    }

    public BinanceKlineClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public List<Kline> fetchKlines(String symbol, String interval, long startTime, long endTime, int limit) {
        String url = "%s?symbol=%s&interval=%s&startTime=%d&endTime=%d&limit=%d"
                .formatted(BASE_URL, symbol, interval, startTime, endTime, limit);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Binance API error: HTTP %d - %s"
                        .formatted(response.statusCode(), response.body()));
            }
            return parseKlines(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to fetch klines for " + symbol, e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to fetch klines for " + symbol, e);
        }
    }

    private List<Kline> parseKlines(String json) throws IOException {
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
}
