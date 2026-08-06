package com.smalistean.propstrategy.marketdownloader;

import com.smalistean.propstrategy.database.Kline;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class BinanceKlineClient {

    private static final String BASE_URL = "https://api.binance.com/api/v3/klines";

    private final HttpClient httpClient;

    public BinanceKlineClient() {
        this(HttpClient.newHttpClient());
    }

    public BinanceKlineClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public List<Kline> fetchKlines(String symbol, String interval, long startTime, long endTime, int limit) {
        String url = "%s?symbol=%s&interval=%s&startTime=%d&endTime=%d&limit=%d"
                .formatted(BASE_URL, symbol, interval, startTime, endTime, limit);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Binance API error: HTTP %d - %s"
                        .formatted(response.statusCode(), response.body()));
            }
            return parseKlines(response.body());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to fetch klines for " + symbol, e);
        }
    }

    private List<Kline> parseKlines(String json) {
        List<Kline> klines = new ArrayList<>();
        String trimmed = json.trim();
        if (trimmed.length() <= 2) {
            return klines;
        }

        for (String entry : splitTopLevelArray(trimmed.substring(1, trimmed.length() - 1))) {
            List<String> fields = splitTopLevelArray(entry);
            if (fields.size() < 6) {
                continue;
            }
            klines.add(new Kline(
                    Instant.ofEpochMilli(Long.parseLong(fields.get(0))),
                    new BigDecimal(fields.get(1)),
                    new BigDecimal(fields.get(2)),
                    new BigDecimal(fields.get(3)),
                    new BigDecimal(fields.get(4)),
                    new BigDecimal(fields.get(5))
            ));
        }
        return klines;
    }

    private static List<String> splitTopLevelArray(String content) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inString = false;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '"') {
                inString = !inString;
            }
            if (!inString) {
                if (c == '[') {
                    depth++;
                } else if (c == ']') {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    parts.add(current.toString().trim());
                    current.setLength(0);
                    continue;
                }
            }
            current.append(c);
        }

        if (!current.isEmpty()) {
            parts.add(current.toString().trim());
        }
        return parts;
    }
}
