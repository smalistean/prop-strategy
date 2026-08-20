package com.smalistean.propstrategy.xvf.signal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 24-hour quote volume per venue and symbol, straight from each venue's ticker endpoint.
 *
 * <h2>Why not the kline tables</h2>
 * The participation cap is the guard that stops XVF sizing into a market that cannot absorb it — REN
 * paid 507% annualised on $289 of weekly volume, and taking that position is how a hedged book turns
 * into a 60% loss. A guard is only as good as the freshness of what it reads, and the kline importers
 * are backfills: Binance 1h currently holds 1,230 rows for the last seven days where a full universe
 * would carry roughly 140,000. Reading liquidity from a stale backfill means the cap silently passes
 * or silently blocks everything, and both failures look like a normal empty book.
 *
 * <p>One request per venue, all symbols. Fresh by construction.
 */
public final class LiveVolume {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LiveVolume() {
    }

    /** Keyed {@code venue|venueSymbol}, value is 24h quote volume in USD. */
    public static Map<String, Double> fetch() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        Map<String, Double> out = new HashMap<>();

        for (JsonNode t : get(client, "https://fapi.binance.com/fapi/v1/ticker/24hr")) {
            out.put("binance|" + t.path("symbol").asText(), t.path("quoteVolume").asDouble());
        }
        for (JsonNode t : get(client, "https://api.bybit.com/v5/market/tickers?category=linear")
                .path("result").path("list")) {
            out.put("bybit|" + t.path("symbol").asText(), t.path("turnover24h").asDouble());
        }
        // Hyperliquid returns [meta, contexts] as parallel arrays rather than one keyed object.
        JsonNode hl = post(client, "https://api.hyperliquid.xyz/info", "{\"type\":\"metaAndAssetCtxs\"}");
        JsonNode universe = hl.get(0).path("universe");
        JsonNode contexts = hl.get(1);
        for (int i = 0; i < universe.size() && i < contexts.size(); i++) {
            out.put("hyperliquid|" + universe.get(i).path("name").asText(),
                    contexts.get(i).path("dayNtlVlm").asDouble());
        }
        return out;
    }

    private static JsonNode get(HttpClient client, String url) {
        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                            .header("User-Agent", "prop-strategy-xvf").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode() + " for " + url);
            }
            return MAPPER.readTree(response.body());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("volume fetch failed: " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static JsonNode post(HttpClient client, String url, String body) {
        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return MAPPER.readTree(response.body());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("volume fetch failed: " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
