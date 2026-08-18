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
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ranks the XVF book from DynamoDB observations and freezes it into the signal table.
 *
 * <h2>Why a frozen book</h2>
 * {@code XvfExecutionApplication} currently re-ranks at startup, so a restart part-way through a
 * rebalance trades a different book from the one it was executing. Writing the book once under a
 * {@code signalRunId} and having execution read it makes that impossible by construction rather than
 * by discipline.
 *
 * <h2>Completeness without a 180-day median</h2>
 * The Postgres signal filters series on {@code payments >= 0.9 * median weekly payments}, which needs
 * 180 days of history - the one thing that would force a large archive into DynamoDB. The median is
 * only a proxy for "how many payments should this series have had", and all three venues answer that
 * directly: Binance {@code fundingIntervalHours}, Bybit {@code fundingInterval} in minutes,
 * Hyperliquid always hourly. So {@code expected = windowHours / intervalHours} and no history is
 * needed beyond the trailing window itself.
 *
 * <p>That also removes two recorded defects: the signal ignoring the published interval, and a series
 * being dropped when its cadence legitimately changes.
 *
 * <h2>One observation per stamp</h2>
 * Observations are hourly samples of a PENDING rate, so a 4-hourly symbol is sampled four times for
 * the same payment. Only the sample taken within an hour of the stamp is counted. Measured on 48
 * hours of overlap with settled history: 10.45ppm mean error at a 1-hour lead against 128.99ppm at
 * 5 hours or more, where the typical Binance payment is 129ppm. A stale observation is not a weak
 * signal, it is noise, so anything older is treated as missing and the completeness filter drops it.
 *
 * <p><b>Duplicated constants.</b> The values below mirror {@code XvfConfig} in the main tree, which
 * this module deliberately does not depend on. That is a drift risk of exactly the kind
 * {@code XvfSignalEngine}'s javadoc warns about, and the mitigation is to diff this book against
 * {@code XvfSignalApplication} until the local path is retired.
 */
public final class XvfSignalHandler implements RequestHandler<Map<String, Object>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int BATCH_SIZE = 25;

    private static final double MIN_SPREAD_ANNUAL_PCT = 20.0;
    private static final int POSITIONS = 20;
    private static final double COMPLETENESS_RATIO = 0.9;
    private static final double MIN_WEEKLY_QUOTE_VOLUME = 500_000;
    /** v1 venues. dYdX is excluded on measurement - see XVF_V1_SCOPE.md. */
    private static final List<String> VENUES = List.of("binance", "bybit", "hyperliquid");

    private static final DynamoDbClient DYNAMO = DynamoDbClient.builder()
            .httpClient(UrlConnectionHttpClient.builder().build()).build();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).build();

    /** One venue's trailing funding for one symbol. */
    private record Leg(String venue, String symbol, double rate, int payments, double weeklyVolume) { }

    private record Candidate(String base, Leg shortLeg, Leg longLeg, double spreadPct, double thinVol) { }

    @Override
    public String handleRequest(Map<String, Object> event, Context context) {
        String fundingTable = required("FUNDING_TABLE");
        String signalTable = required("SIGNAL_TABLE");
        int lookbackDays = Integer.parseInt(System.getenv().getOrDefault("LOOKBACK_DAYS", "7"));
        long retentionDays = Long.parseLong(System.getenv().getOrDefault("RETENTION_DAYS", "365"));

        Instant now = Instant.now().truncatedTo(ChronoUnit.HOURS);
        Instant from = now.minus(lookbackDays, ChronoUnit.DAYS);
        int windowHours = lookbackDays * 24;

        Map<String, Integer> intervalHours = fundingIntervals();
        Map<String, Double> volume24h = liveVolume();
        log(context, "intervals for %,d symbols, volume for %,d".formatted(
                intervalHours.size(), volume24h.size()));

        // Sum one observation per stamp across the window.
        Map<String, double[]> summed = new HashMap<>();   // venue|symbol -> [rateSum, payments]
        int scanned = 0;
        for (Instant hour = from; hour.isBefore(now); hour = hour.plus(1, ChronoUnit.HOURS)) {
            for (String venue : VENUES) {
                scanned += accumulate(fundingTable, venue, hour, summed);
            }
        }
        log(context, "scanned %,d observations over %d hours".formatted(scanned, windowHours));

        List<Leg> legs = new ArrayList<>();
        for (var entry : summed.entrySet()) {
            String[] key = entry.getKey().split("\\|", 2);
            double[] agg = entry.getValue();
            int interval = intervalHours.getOrDefault(entry.getKey(),
                    "hyperliquid".equals(key[0]) ? 1 : 0);
            if (interval <= 0) {
                continue;   // no published interval: cannot judge completeness, so do not guess
            }
            double expected = (double) windowHours / interval;
            if (agg[1] < COMPLETENESS_RATIO * expected) {
                continue;   // partial window reads as a LOW rate, because the figure is a sum
            }
            legs.add(new Leg(key[0], key[1], agg[0], (int) agg[1],
                    volume24h.getOrDefault(entry.getKey(), 0.0) * 7));
        }
        log(context, "%,d legs pass completeness".formatted(legs.size()));

        List<Candidate> book = rank(legs, lookbackDays);
        String runId = now.toString();
        write(signalTable, runId, now, book, lookbackDays, legs.size(),
                now.plus(retentionDays, ChronoUnit.DAYS).getEpochSecond());

        String summary = "SIGNAL %s: %d candidates, %d legs, %d hours".formatted(
                runId, book.size(), legs.size(), windowHours);
        log(context, summary);
        return summary;
    }

    /** Adds one venue-hour of observations, counting only the sample that settles its stamp. */
    private static int accumulate(String table, String venue, Instant hour,
                                  Map<String, double[]> into) {
        int seen = 0;
        Map<String, AttributeValue> start = null;
        do {
            QueryRequest.Builder request = QueryRequest.builder().tableName(table)
                    .keyConditionExpression("venue_hour = :k")
                    .expressionAttributeValues(Map.of(":k",
                            AttributeValue.builder().s(venue + "#" + hour).build()));
            if (start != null) {
                request.exclusiveStartKey(start);
            }
            QueryResponse response = DYNAMO.query(request.build());
            for (Map<String, AttributeValue> item : response.items()) {
                seen++;
                AttributeValue stampAttr = item.get("target_stamp");
                if (stampAttr == null || stampAttr.n() == null) {
                    continue;
                }
                Instant stamp = Instant.ofEpochMilli(Long.parseLong(stampAttr.n()));
                // Only the sample within an hour of its stamp. See the class javadoc: at a five-hour
                // lead the error equals the entire payment.
                long leadMinutes = Duration.between(hour, stamp).toMinutes();
                if (leadMinutes <= 0 || leadMinutes > 60) {
                    continue;
                }
                String key = venue + "|" + item.get("venue_symbol").s();
                double rate = Double.parseDouble(item.get("funding_rate").n());
                into.compute(key, (k, v) -> v == null
                        ? new double[] {rate, 1}
                        : new double[] {v[0] + rate, v[1] + 1});
            }
            start = response.lastEvaluatedKey().isEmpty() ? null : response.lastEvaluatedKey();
        } while (start != null);
        return seen;
    }

    /** Widest cross-venue spread per base, filtered and ranked. */
    private static List<Candidate> rank(List<Leg> legs, int lookbackDays) {
        Map<String, List<Leg>> byBase = new HashMap<>();
        for (Leg leg : legs) {
            byBase.computeIfAbsent(normaliseBase(leg.venue(), leg.symbol()),
                    k -> new ArrayList<>()).add(leg);
        }
        List<Candidate> out = new ArrayList<>();
        for (var entry : byBase.entrySet()) {
            List<Leg> venueLegs = entry.getValue();
            // Both legs must be on DIFFERENT venues, and the widest legitimate combination is not
            // necessarily the extremes of the whole set once a venue is excluded.
            Leg best = null;
            Leg bestCheap = null;
            double bestSpread = Double.NEGATIVE_INFINITY;
            for (Leg candidate : venueLegs) {
                Leg cheap = venueLegs.stream()
                        .filter(l -> !l.venue().equals(candidate.venue()))
                        .min(Comparator.comparingDouble(Leg::rate)).orElse(null);
                if (cheap != null && candidate.rate() - cheap.rate() > bestSpread) {
                    bestSpread = candidate.rate() - cheap.rate();
                    best = candidate;
                    bestCheap = cheap;
                }
            }
            if (best == null) {
                continue;
            }
            double spread = bestSpread * (365.0 / lookbackDays) * 100;
            double thin = Math.min(best.weeklyVolume(), bestCheap.weeklyVolume());
            if (spread > MIN_SPREAD_ANNUAL_PCT && thin >= MIN_WEEKLY_QUOTE_VOLUME) {
                out.add(new Candidate(entry.getKey(), best, bestCheap, spread, thin));
            }
        }
        out.sort(Comparator.comparingDouble(Candidate::spreadPct).reversed());
        return out.size() > POSITIONS ? out.subList(0, POSITIONS) : out;
    }

    // ---------- venue metadata ----------

    /** Published funding interval per {@code venue|symbol}, in hours. */
    private static Map<String, Integer> fundingIntervals() {
        Map<String, Integer> out = new HashMap<>();
        for (JsonNode n : get("https://fapi.binance.com/fapi/v1/fundingInfo")) {
            out.put("binance|" + n.path("symbol").asText(), n.path("fundingIntervalHours").asInt(8));
        }
        // Binance publishes fundingInfo only for symbols with an ADJUSTED interval; everything else
        // is the 8-hour default and simply absent from that response.
        for (JsonNode n : get("https://fapi.binance.com/fapi/v1/premiumIndex")) {
            out.putIfAbsent("binance|" + n.path("symbol").asText(), 8);
        }
        for (JsonNode n : get("https://api.bybit.com/v5/market/instruments-info?category=linear&limit=1000")
                .path("result").path("list")) {
            int minutes = n.path("fundingInterval").asInt(480);
            out.put("bybit|" + n.path("symbol").asText(), Math.max(1, minutes / 60));
        }
        return out;
    }

    /** 24h quote volume per {@code venue|symbol}; the participation cap needs a live figure. */
    private static Map<String, Double> liveVolume() {
        Map<String, Double> out = new HashMap<>();
        for (JsonNode t : get("https://fapi.binance.com/fapi/v1/ticker/24hr")) {
            out.put("binance|" + t.path("symbol").asText(), t.path("quoteVolume").asDouble());
        }
        for (JsonNode t : get("https://api.bybit.com/v5/market/tickers?category=linear")
                .path("result").path("list")) {
            out.put("bybit|" + t.path("symbol").asText(), t.path("turnover24h").asDouble());
        }
        JsonNode hl = post("https://api.hyperliquid.xyz/info", "{\"type\":\"metaAndAssetCtxs\"}");
        JsonNode universe = hl.get(0).path("universe");
        JsonNode contexts = hl.get(1);
        for (int i = 0; i < universe.size() && i < contexts.size(); i++) {
            out.put("hyperliquid|" + universe.get(i).path("name").asText(),
                    contexts.get(i).path("dayNtlVlm").asDouble());
        }
        return out;
    }

    /** Mirrors {@code XvfConfig.normaliseBase} and migration V14. */
    static String normaliseBase(String venue, String venueSymbol) {
        String raw = "hyperliquid".equals(venue) ? venueSymbol
                : (venueSymbol.endsWith("USDT") || venueSymbol.endsWith("USDC")
                        ? venueSymbol.substring(0, venueSymbol.length() - 4) : venueSymbol);
        for (String prefix : new String[] {"1000000", "100000", "10000", "1000"}) {
            if (raw.startsWith(prefix) && raw.length() > prefix.length()
                    && Character.isUpperCase(raw.charAt(prefix.length()))) {
                return raw.substring(prefix.length());
            }
        }
        if (raw.startsWith("1M") && raw.length() > 2 && Character.isUpperCase(raw.charAt(2))) {
            return raw.substring(2);
        }
        if (raw.startsWith("k") && raw.length() > 1 && Character.isUpperCase(raw.charAt(1))) {
            return raw.substring(1);
        }
        return raw;
    }

    // ---------- persistence ----------

    private static void write(String table, String runId, Instant cutoff, List<Candidate> book,
                              int lookbackDays, int legsConsidered, long expiresAt) {
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        Map<String, AttributeValue> meta = new HashMap<>();
        meta.put("pk", s("RUN#" + runId));
        meta.put("sk", s("META"));
        meta.put("signal_run_id", s(runId));
        meta.put("cutoff_utc", s(cutoff.toString()));
        meta.put("generated_at", s(Instant.now().toString()));
        meta.put("lookback_days", n(lookbackDays));
        meta.put("legs_considered", n(legsConsidered));
        meta.put("positions", n(book.size()));
        meta.put("venues", s(String.join(",", VENUES)));
        meta.put("min_spread_pct", n(MIN_SPREAD_ANNUAL_PCT));
        meta.put("status", s(book.isEmpty() ? "EMPTY" : "READY"));
        meta.put("expires_at", n(expiresAt));
        items.add(meta);

        int rank = 0;
        for (Candidate c : book) {
            rank++;
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("pk", s("RUN#" + runId));
            item.put("sk", s("RANK#%02d".formatted(rank)));
            item.put("rank", n(rank));
            item.put("base", s(c.base()));
            item.put("short_venue", s(c.shortLeg().venue()));
            item.put("short_symbol", s(c.shortLeg().symbol()));
            item.put("short_rate", n(c.shortLeg().rate()));
            item.put("long_venue", s(c.longLeg().venue()));
            item.put("long_symbol", s(c.longLeg().symbol()));
            item.put("long_rate", n(c.longLeg().rate()));
            item.put("spread_annual_pct", n(c.spreadPct()));
            item.put("thin_leg_weekly_volume", n(c.thinVol()));
            item.put("expires_at", n(expiresAt));
            items.add(item);
        }

        // The pointer is written LAST, so a reader never follows LATEST to a half-written run.
        Map<String, AttributeValue> latest = new HashMap<>();
        latest.put("pk", s("LATEST"));
        latest.put("sk", s("META"));
        latest.put("signal_run_id", s(runId));
        latest.put("generated_at", s(Instant.now().toString()));
        latest.put("positions", n(book.size()));

        putAll(table, items);
        putAll(table, List.of(latest));
    }

    private static void putAll(String table, List<Map<String, AttributeValue>> items) {
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
                    throw new IllegalStateException("signal write still unprocessed after 10 attempts");
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

    // ---------- plumbing ----------

    private static AttributeValue s(String v) {
        return AttributeValue.builder().s(v).build();
    }

    private static AttributeValue n(double v) {
        return AttributeValue.builder().n(BigDecimal.valueOf(v).toPlainString()).build();
    }

    private static JsonNode get(String url) {
        return request(HttpRequest.newBuilder(URI.create(url)).GET(), url);
    }

    private static JsonNode post(String url, String body) {
        return request(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)), url);
    }

    private static JsonNode request(HttpRequest.Builder builder, String url) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                HttpResponse<String> response = HTTP.send(
                        builder.copy().timeout(Duration.ofSeconds(30))
                                .header("User-Agent", "prop-strategy-xvf").build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("HTTP " + response.statusCode() + " for " + url);
                }
                return MAPPER.readTree(response.body());
            } catch (java.io.IOException | IllegalStateException e) {
                last = e instanceof IllegalStateException i ? i
                        : new IllegalStateException("failed: " + url, e);
                sleep(1_000L * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
        }
        throw last;
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
            throw new IllegalStateException("interrupted", e);
        }
    }
}
