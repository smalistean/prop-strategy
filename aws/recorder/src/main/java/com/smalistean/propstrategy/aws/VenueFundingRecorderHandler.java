package com.smalistean.propstrategy.aws;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Records the current funding state of all four XVF venues once an hour.
 *
 * <h2>What this fixes</h2>
 * XVF's signal is only meaningful on data that is current, and keeping it current depended on running
 * {@code scripts/xvf-refresh.sh} by hand. On 2026-08-15 that had not happened for three days, and the
 * effect was not a visible failure: of the symbols present, only 33 of 730 on Binance and 40 of 775 on
 * Bybit had a complete trailing window, while dYdX and Hyperliquid contributed zero. The book came out
 * at 4 names instead of 20.
 *
 * <h2>These are OBSERVATIONS, not settled payments - the distinction is the whole design</h2>
 * Every endpoint used here returns the rate that <em>will</em> settle at the next stamp, not one that
 * has settled. It moves until the stamp lands. So these rows are not interchangeable with the settled
 * history in {@code <venue>_perp_funding_rate}, and blending the two silently is exactly the shape of
 * the bug that once made cash-and-carry read Sharpe 2.16 when the truth was 1.29 - two overlapping
 * sources for one payment, double counted, with no error anywhere.
 *
 * <p>They are therefore written to their own keyspace, exported to their own table
 * ({@code venue_funding_observation}), and carry {@code observed_at} alongside the stamp they target
 * so the difference stays visible. Settled history remains the authority; observations exist to keep
 * the freshness guard satisfied between full refreshes.
 *
 * <h2>One call per venue</h2>
 * All four expose every symbol's current funding in a single request - 868, 826, 232 and 296 symbols
 * respectively. Walking per-symbol history endpoints instead would be thousands of calls and could not
 * finish inside a Lambda; the dYdX full history took 6.5 hours locally.
 *
 * <h2>Idempotency</h2>
 * Partitioned by {@code venue#observed_hour}, so re-running an hour rewrites the same items rather
 * than appending a second copy. The stamp each observation targets is an attribute, not part of the
 * key, because the key must be stable across the retries that make the write safe.
 */
public final class VenueFundingRecorderHandler implements RequestHandler<Map<String, Object>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int BATCH_SIZE = 25;
    private static final long HTTP_RETRY_HORIZON_SECONDS = 120;
    private static final long INVOCATION_RETRY_BUDGET_SECONDS = 600;

    /** Below this many symbols a venue is treated as failed rather than quiet. */
    private static final int MIN_SYMBOLS_PER_VENUE = 100;

    private static final DynamoDbClient DYNAMO = DynamoDbClient.builder()
            .httpClient(UrlConnectionHttpClient.builder().build())
            .build();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** One venue's current funding for one symbol. */
    private record Observation(String venueSymbol, BigDecimal rate, Long targetStampMillis) { }

    private Instant retryBudgetEnd;

    @Override
    public String handleRequest(Map<String, Object> event, Context context) {
        this.retryBudgetEnd = Instant.now().plusSeconds(INVOCATION_RETRY_BUDGET_SECONDS);
        String table = required("TABLE_NAME");
        long retentionDays = Long.parseLong(System.getenv().getOrDefault("RETENTION_DAYS", "30"));

        Instant observedAt = Instant.now();
        Instant hour = observedAt.truncatedTo(ChronoUnit.HOURS);
        long expiresAt = hour.plus(retentionDays, ChronoUnit.DAYS).getEpochSecond();

        Map<String, List<Observation>> byVenue = new HashMap<>();
        byVenue.put("binance", binance());
        byVenue.put("bybit", bybit());
        byVenue.put("hyperliquid", hyperliquid(hour));
        byVenue.put("dydx", dydx(hour));

        List<String> thin = new ArrayList<>();
        int total = 0;
        StringBuilder counts = new StringBuilder();
        for (var entry : byVenue.entrySet()) {
            String venue = entry.getKey();
            List<Observation> observations = entry.getValue();
            if (observations.size() < MIN_SYMBOLS_PER_VENUE) {
                thin.add(venue + "=" + observations.size());
            }
            write(table, items(venue, hour, observedAt, expiresAt, observations));
            total += observations.size();
            if (!counts.isEmpty()) {
                counts.append(',');
            }
            counts.append(venue).append('=').append(observations.size());
            log(context, "%-12s %,4d symbols".formatted(venue, observations.size()));
        }

        // A venue returning almost nothing is the failure this exists to prevent, so it must not be
        // recorded as a quiet hour. The guard downstream counts usable symbols per venue; letting a
        // near-empty venue through here would simply move the silent failure one step later.
        if (!thin.isEmpty()) {
            throw new IllegalStateException("venues returned too few symbols: " + String.join(", ", thin)
                    + ". Refusing to record a partial hour as complete.");
        }

        writeManifest(table, hour, expiresAt, counts.toString(), total);
        String summary = "FUNDING OBSERVED %s: %,d symbols across %d venues"
                .formatted(hour, total, byVenue.size());
        log(context, summary);
        return summary;
    }

    // ---------- venue adapters, one request each ----------

    /** {@code lastFundingRate} is the rate pending at {@code nextFundingTime}, despite the name. */
    private List<Observation> binance() {
        List<Observation> out = new ArrayList<>();
        for (JsonNode n : get("https://fapi.binance.com/fapi/v1/premiumIndex")) {
            String symbol = n.path("symbol").asText();
            if (!symbol.endsWith("USDT") && !symbol.endsWith("USDC")) {
                continue;
            }
            JsonNode rate = n.get("lastFundingRate");
            if (rate == null || rate.asText().isBlank()) {
                continue;
            }
            out.add(new Observation(symbol, new BigDecimal(rate.asText()),
                    n.path("nextFundingTime").asLong(0)));
        }
        return out;
    }

    private List<Observation> bybit() {
        List<Observation> out = new ArrayList<>();
        for (JsonNode n : get("https://api.bybit.com/v5/market/tickers?category=linear")
                .path("result").path("list")) {
            String rate = n.path("fundingRate").asText("");
            if (rate.isBlank()) {
                continue;
            }
            out.add(new Observation(n.path("symbol").asText(), new BigDecimal(rate),
                    n.path("nextFundingTime").asLong(0)));
        }
        return out;
    }

    /**
     * Hyperliquid publishes an hourly rate with no stamp field, so the stamp is the top of the next
     * hour. Its funding interval is 1h for every one of its 232 coins, which is what makes that safe.
     */
    private List<Observation> hyperliquid(Instant hour) {
        JsonNode response = post("https://api.hyperliquid.xyz/info", "{\"type\":\"metaAndAssetCtxs\"}");
        JsonNode universe = response.get(0).path("universe");
        JsonNode contexts = response.get(1);
        long stamp = hour.plus(1, ChronoUnit.HOURS).toEpochMilli();
        List<Observation> out = new ArrayList<>();
        for (int i = 0; i < universe.size() && i < contexts.size(); i++) {
            String funding = contexts.get(i).path("funding").asText("");
            if (funding.isBlank()) {
                continue;
            }
            out.add(new Observation(universe.get(i).path("name").asText(),
                    new BigDecimal(funding), stamp));
        }
        return out;
    }

    /** dYdX funding is hourly too, so the same top-of-next-hour reasoning applies. */
    private List<Observation> dydx(Instant hour) {
        JsonNode markets = get("https://indexer.dydx.trade/v4/perpetualMarkets").path("markets");
        long stamp = hour.plus(1, ChronoUnit.HOURS).toEpochMilli();
        List<Observation> out = new ArrayList<>();
        markets.fields().forEachRemaining(field -> {
            String rate = field.getValue().path("nextFundingRate").asText("");
            if (!rate.isBlank()) {
                out.add(new Observation(field.getValue().path("ticker").asText(field.getKey()),
                        new BigDecimal(rate), stamp));
            }
        });
        return out;
    }

    // ---------- storage ----------

    private static List<Map<String, AttributeValue>> items(
            String venue, Instant hour, Instant observedAt, long expiresAt,
            List<Observation> observations) {
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        for (Observation o : observations) {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("venue_hour", string(venue + "#" + hour));
            item.put("venue_symbol", string(o.venueSymbol()));
            item.put("venue", string(venue));
            item.put("observed_hour", string(hour.toString()));
            item.put("observed_at", string(observedAt.toString()));
            item.put("funding_rate", number(o.rate()));
            if (o.targetStampMillis() != null && o.targetStampMillis() > 0) {
                item.put("target_stamp", number(BigDecimal.valueOf(o.targetStampMillis())));
            }
            item.put("expires_at", number(BigDecimal.valueOf(expiresAt)));
            items.add(item);
        }
        return items;
    }

    private static void writeManifest(String table, Instant hour, long expiresAt,
                                      String counts, int total) {
        Map<String, AttributeValue> manifest = new HashMap<>();
        manifest.put("venue_hour", string("MANIFEST#" + hour));
        manifest.put("venue_symbol", string("MANIFEST"));
        manifest.put("observed_hour", string(hour.toString()));
        manifest.put("venue_counts", string(counts));
        manifest.put("item_count", number(BigDecimal.valueOf(total)));
        manifest.put("expires_at", number(BigDecimal.valueOf(expiresAt)));
        write(table, List.of(manifest));
    }

    /** See {@code DeribitRecorderHandler.write} - UnprocessedItems must be retried, not ignored. */
    private static void write(String table, List<Map<String, AttributeValue>> items) {
        for (int start = 0; start < items.size(); start += BATCH_SIZE) {
            List<WriteRequest> batch = new ArrayList<>();
            for (Map<String, AttributeValue> item : items.subList(
                    start, Math.min(start + BATCH_SIZE, items.size()))) {
                batch.add(WriteRequest.builder()
                        .putRequest(PutRequest.builder().item(item).build()).build());
            }
            Map<String, List<WriteRequest>> pending = Map.of(table, batch);
            for (int attempt = 1; !pending.isEmpty(); attempt++) {
                if (attempt > 10) {
                    throw new IllegalStateException("DynamoDB still returning unprocessed items after "
                            + "10 attempts; the hour is incomplete");
                }
                BatchWriteItemResponse response = DYNAMO.batchWriteItem(
                        BatchWriteItemRequest.builder().requestItems(pending).build());
                pending = response.unprocessedItems();
                if (!pending.isEmpty()) {
                    sleep(Math.min(100L * (1L << attempt), 5_000L));
                }
            }
        }
    }

    // ---------- http ----------

    private JsonNode get(String url) {
        return request(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60)).GET(), url);
    }

    private JsonNode post(String url, String body) {
        return request(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)), url);
    }

    /** Retries on a horizon rather than an attempt count, bounded by the invocation budget. */
    private JsonNode request(HttpRequest.Builder builder, String url) {
        Instant callDeadline = Instant.now().plusSeconds(HTTP_RETRY_HORIZON_SECONDS);
        Instant deadline = callDeadline.isBefore(retryBudgetEnd) ? callDeadline : retryBudgetEnd;
        RuntimeException last = null;
        for (int attempt = 1; ; attempt++) {
            try {
                HttpResponse<String> response = HTTP.send(
                        builder.copy().header("User-Agent", "prop-strategy-xvf").build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("HTTP " + response.statusCode() + " for " + url);
                }
                return MAPPER.readTree(response.body());
            } catch (java.io.IOException | IllegalStateException e) {
                last = e instanceof IllegalStateException illegal
                        ? illegal : new IllegalStateException("Request failed: " + url, e);
                if (Instant.now().isAfter(deadline)) {
                    throw last;
                }
                sleep(Math.min(1_000L * attempt, 15_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted", e);
            }
        }
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(BigDecimal value) {
        return AttributeValue.builder().n(value.toPlainString()).build();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("environment variable " + name + " is not set");
        }
        return value;
    }

    private static void log(Context context, String message) {
        if (context != null && context.getLogger() != null) {
            context.getLogger().log(message);
        } else {
            System.out.println(message);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }
}
