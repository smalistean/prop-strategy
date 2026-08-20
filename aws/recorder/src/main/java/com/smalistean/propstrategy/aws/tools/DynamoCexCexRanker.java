package com.smalistean.propstrategy.aws.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

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
 * One-off comparison tool. Ranks CEX-CEX (binance/bybit only) candidates straight from the
 * xvf-funding-observation DynamoDB table, mirroring {@code XvfSignalHandler}'s algorithm exactly, so
 * the result can be diffed against {@code XvfSignalEngine}'s Postgres-sourced ranking for the same
 * wall-clock window. Read-only: queries DynamoDB, never writes anything, and never touches the
 * deployed Lambda's own configuration.
 */
public final class DynamoCexCexRanker {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double COMPLETENESS_RATIO = 0.9;
    private static final double MIN_SPREAD_ANNUAL_PCT = 20.0;
    private static final double MIN_WEEKLY_QUOTE_VOLUME = 500_000;
    private static final List<String> VENUES = List.of("binance", "bybit");

    private static final DynamoDbClient DYNAMO = DynamoDbClient.builder()
            .httpClient(UrlConnectionHttpClient.builder().build()).build();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).build();

    private record Leg(String venue, String symbol, double rate, int payments, double weeklyVolume) { }

    private record Candidate(String base, Leg shortLeg, Leg longLeg, double spreadPct, double thinVol) { }

    public static void main(String[] args) {
        String table = args.length > 0 ? args[0] : "xvf-funding-observation";
        int lookbackDays = args.length > 1 ? Integer.parseInt(args[1]) : 3;

        Instant now = Instant.now().truncatedTo(ChronoUnit.HOURS);
        Instant from = now.minus(lookbackDays, ChronoUnit.DAYS);
        int windowHours = lookbackDays * 24;

        Map<String, Integer> intervalHours = fundingIntervals();
        Map<String, Double> volume24h = liveVolume();
        System.err.printf("intervals for %,d symbols, volume for %,d%n",
                intervalHours.size(), volume24h.size());

        Map<String, double[]> summed = new HashMap<>();
        int scanned = 0;
        for (Instant hour = from; hour.isBefore(now); hour = hour.plus(1, ChronoUnit.HOURS)) {
            for (String venue : VENUES) {
                scanned += accumulate(table, venue, hour, summed);
            }
        }
        System.err.printf("scanned %,d observations over %d hours (%s to %s)%n",
                scanned, windowHours, from, now);

        List<Leg> legs = new ArrayList<>();
        for (var entry : summed.entrySet()) {
            String[] key = entry.getKey().split("\\|", 2);
            double[] agg = entry.getValue();
            int interval = intervalHours.getOrDefault(entry.getKey(), 0);
            if (interval <= 0) {
                continue;
            }
            double expected = (double) windowHours / interval;
            if (agg[1] < COMPLETENESS_RATIO * expected) {
                continue;
            }
            legs.add(new Leg(key[0], key[1], agg[0], (int) agg[1],
                    volume24h.getOrDefault(entry.getKey(), 0.0) * 7));
        }
        System.err.printf("%,d legs pass completeness%n", legs.size());

        List<Candidate> book = rank(legs, lookbackDays);
        System.out.printf("%n=== DynamoDB CEX-CEX, %d-day lookback, cutoff %s ===%n", lookbackDays, now);
        System.out.printf("%-10s %-22s %-22s %9s %14s%n", "base", "SHORT", "LONG", "spread%", "thin vol");
        for (Candidate c : book) {
            System.out.printf("%-10s %-22s %-22s %8.1f%% %14s%n",
                    c.base(), c.shortLeg().venue() + " " + c.shortLeg().symbol(),
                    c.longLeg().venue() + " " + c.longLeg().symbol(), c.spreadPct(),
                    String.format("%,.0f", c.thinVol()));
        }
    }

    private static int accumulate(String table, String venue, Instant hour, Map<String, double[]> into) {
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

    private static List<Candidate> rank(List<Leg> legs, int lookbackDays) {
        Map<String, List<Leg>> byBase = new HashMap<>();
        for (Leg leg : legs) {
            byBase.computeIfAbsent(normaliseBase(leg.venue(), leg.symbol()),
                    k -> new ArrayList<>()).add(leg);
        }
        List<Candidate> out = new ArrayList<>();
        for (var entry : byBase.entrySet()) {
            List<Leg> venueLegs = entry.getValue();
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
        return out;
    }

    private static Map<String, Integer> fundingIntervals() {
        Map<String, Integer> out = new HashMap<>();
        for (JsonNode n : get("https://fapi.binance.com/fapi/v1/fundingInfo")) {
            out.put("binance|" + n.path("symbol").asText(), n.path("fundingIntervalHours").asInt(8));
        }
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

    private static Map<String, Double> liveVolume() {
        Map<String, Double> out = new HashMap<>();
        for (JsonNode t : get("https://fapi.binance.com/fapi/v1/ticker/24hr")) {
            out.put("binance|" + t.path("symbol").asText(), t.path("quoteVolume").asDouble());
        }
        for (JsonNode t : get("https://api.bybit.com/v5/market/tickers?category=linear")
                .path("result").path("list")) {
            out.put("bybit|" + t.path("symbol").asText(), t.path("turnover24h").asDouble());
        }
        return out;
    }

    static String normaliseBase(String venue, String venueSymbol) {
        String raw = venueSymbol.endsWith("USDT") || venueSymbol.endsWith("USDC")
                ? venueSymbol.substring(0, venueSymbol.length() - 4) : venueSymbol;
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

    private static JsonNode get(String url) {
        return request(HttpRequest.newBuilder(URI.create(url)).GET(), url);
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

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private DynamoCexCexRanker() {
    }
}
