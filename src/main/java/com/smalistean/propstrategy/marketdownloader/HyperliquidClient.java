package com.smalistean.propstrategy.marketdownloader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Shared access to the Hyperliquid {@code /info} endpoint.
 *
 * <h2>Why the throttle lives here rather than in each importer</h2>
 * The endpoint is weight-limited <b>per IP</b>, not per connection or per process-component. A first
 * funding import at four unpaced threads completed 114 of 232 coins and lost the rest to HTTP 429.
 * Pacing inside one importer fixes that importer; it does not help if a second importer runs
 * alongside it, because the two would each keep their own spacing and jointly double the request
 * rate. A single static gate is the only version that stays correct when funding and candles are
 * imported at the same time.
 *
 * <p>Backoff reaches roughly 72 seconds by the final attempt. An earlier 0.5-8s range was ample for a
 * dropped connection and far too short for a weight limit that persists while other threads keep
 * consuming the same budget.
 */
public final class HyperliquidClient {

    private static final String INFO = "https://api.hyperliquid.xyz/info";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Minimum spacing between requests, enforced across every caller in this JVM. */
    private static final long MIN_REQUEST_INTERVAL_MS = Long.getLong("hlIntervalMs", 900L);
    private static final Object THROTTLE = new Object();
    private static long nextRequestAt = 0L;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public JsonNode post(String body) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 6; attempt++) {
            throttle();
            try {
                HttpResponse<String> response = http.send(
                        HttpRequest.newBuilder(URI.create(INFO))
                                .header("Content-Type", "application/json")
                                .timeout(Duration.ofSeconds(60))
                                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 429 || response.statusCode() >= 500) {
                    throw new IllegalStateException("HTTP " + response.statusCode());
                }
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
                }
                return MAPPER.readTree(response.body());
            } catch (java.io.IOException | IllegalStateException e) {
                last = e instanceof IllegalStateException illegal
                        ? illegal : new IllegalStateException("Request failed", e);
                sleep(2_000L * attempt * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted", e);
            }
        }
        throw last;
    }

    private static void throttle() {
        long wait;
        synchronized (THROTTLE) {
            long now = System.currentTimeMillis();
            long at = Math.max(now, nextRequestAt);
            nextRequestAt = at + MIN_REQUEST_INTERVAL_MS;
            wait = at - now;
        }
        if (wait > 0) {
            sleep(wait);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
