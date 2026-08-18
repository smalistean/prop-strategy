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
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
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
 * Records one Deribit option-chain snapshot into DynamoDB, then returns.
 *
 * <h2>Why this runs in Lambda rather than on a laptop</h2>
 * A missed hour is permanent - Deribit serves trade history for free but not quote history, so the
 * data only exists if it was captured at the time. Two home-network DNS outages in 24 hours cost 9
 * hours and nearly a tenth. Nothing about the compute needs a cloud; the availability does.
 *
 * <h2>Key schema, and why not the obvious one</h2>
 * Partition key is {@code snapshot_hour#underlying}, not {@code snapshot_hour} alone. Every item in
 * one snapshot sharing a partition key would put all 4,046 writes in a single partition, and a
 * DynamoDB partition is capped at 1,000 WCU/sec regardless of how much table capacity is provisioned
 * - so the write would throttle for at least four seconds on the partition limit alone. Splitting by
 * underlying gives nine partitions whose largest (BTC, 818 instruments) sits comfortably under the
 * ceiling, and the exporter still reads one hour with nine Query calls instead of a scan.
 *
 * <h2>Idempotency</h2>
 * The snapshot instant is truncated to the hour, so a retry inside the same hour overwrites the same
 * items rather than creating a second, partial copy of the hour. That is what makes the Lambda async
 * retry safe, and it is the same property that let a manual re-run recover the 05:00 hour on
 * 2026-08-16 three minutes after the scheduled attempt failed.
 *
 * <h2>Failure behaviour</h2>
 * Throws on any unrecoverable error. A thrown exception is what marks the invocation failed, which is
 * what triggers Lambda's asynchronous retry and, after that is exhausted, the on-failure destination.
 * Returning normally after a partial write would look like success and lose the hour silently.
 *
 * <p>Public market data only - no API key, and it places no orders.
 */
public final class DeribitRecorderHandler implements RequestHandler<Map<String, Object>, String> {

    private static final String API = "https://www.deribit.com/api/v2/public/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** DynamoDB accepts at most 25 items per BatchWriteItem call. */
    private static final int BATCH_SIZE = 25;

    /**
     * Horizon for retrying one HTTP call, in seconds.
     *
     * <p>The original on-laptop version retried four times with 1s/4s/9s backoff - a 14-second
     * horizon. The outage on 2026-08-16 lasted at least three minutes, so it never had a chance.
     *
     * <p>Raised from 120s after 2026-08-18, when Deribit returned HTTP 503 to this Lambda for roughly
     * two hours while answering normally from a residential IP. Lambda egresses from a shared AWS NAT
     * pool and Deribit rate-limits by IP, so another tenant's traffic can throttle a caller making six
     * requests an hour. No per-call horizon survives two hours - Lambda's own ceiling is 900s - which
     * is why the schedule now makes three attempts an hour rather than one.
     */
    private static final long HTTP_RETRY_HORIZON_SECONDS = 240;

    /**
     * Ceiling on retrying across the whole invocation, in seconds.
     *
     * <p>Needed because the per-call horizon is per call: six calls each burning their own 120
     * seconds would run for twelve minutes and be killed by the Lambda timeout mid-write, which
     * produces a half-written hour and no useful error. This budget is checked alongside the per-call
     * horizon so the handler always gives up while it still has time to fail cleanly.
     */
    private static final long INVOCATION_RETRY_BUDGET_SECONDS = 600;

    // Created once per container rather than per invocation. At one invocation an hour most calls are
    // cold starts anyway, but a warm container should not pay to rebuild the client or reload creds.
    private static final DynamoDbClient DYNAMO = DynamoDbClient.builder()
            .httpClient(UrlConnectionHttpClient.builder().build())
            .build();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Wall-clock point past which no further HTTP retry is started. Set once per invocation. */
    private Instant retryBudgetEnd;

    @Override
    public String handleRequest(Map<String, Object> event, Context context) {
        this.retryBudgetEnd = Instant.now().plusSeconds(INVOCATION_RETRY_BUDGET_SECONDS);
        String table = required("TABLE_NAME");
        List<String> currencies = List.of(
                System.getenv().getOrDefault("DERIBIT_CURRENCIES", "BTC,ETH,USDC")
                        .trim().toUpperCase().split(","));
        long retentionDays = Long.parseLong(System.getenv().getOrDefault("RETENTION_DAYS", "90"));

        Instant snapshot = Instant.now().truncatedTo(ChronoUnit.HOURS);
        long expiresAt = snapshot.plus(retentionDays, ChronoUnit.DAYS).getEpochSecond();

        // The schedule fires three times an hour so a transient venue outage does not cost the hour.
        // Every run after the first is a no-op: the manifest is written last and only on success, so
        // its presence means the hour is complete. Without this the later runs would re-fetch and
        // overwrite a good hour with a later snapshot for no benefit.
        if (alreadyRecorded(table, snapshot)) {
            String done = "ALREADY RECORDED " + snapshot;
            log(context, done);
            return done;
        }

        int total = 0;
        Map<String, Integer> byUnderlying = new HashMap<>();
        for (String currency : currencies) {
            // Expiry comes from get_instruments rather than from parsing "16AUG26" out of the
            // instrument name. The name carries no time of day, and inferring one would silently
            // shift every annualised rate computed from it.
            Map<String, Long> expiries = new HashMap<>();
            for (JsonNode instrument : call("get_instruments?currency=" + currency
                    + "&kind=option&expired=false")) {
                expiries.put(instrument.get("instrument_name").asText(),
                        instrument.get("expiration_timestamp").asLong());
            }
            JsonNode book = call("get_book_summary_by_currency?currency=" + currency + "&kind=option");
            List<Map<String, AttributeValue>> items =
                    toItems(book, expiries, snapshot, expiresAt);
            for (Map<String, AttributeValue> item : items) {
                byUnderlying.merge(item.get("underlying").s(), 1, Integer::sum);
            }
            write(table, items);
            total += items.size();
            log(context, "%s %,d instruments".formatted(currency, items.size()));
        }

        // A snapshot that returns almost nothing means Deribit answered with a truncated or empty
        // book. Succeeding on that would write a hole that looks like a recorded hour, which is worse
        // than failing, because the retry never fires and nothing ever reports it.
        if (total < 1_000) {
            throw new IllegalStateException("only " + total + " instruments captured for " + snapshot
                    + "; expected roughly 4,000. Refusing to record a partial hour as complete.");
        }
        // Written LAST, and only once every chain has landed. Its presence is the marker that the
        // hour is complete: the exporter reads it to learn which partitions to Query and how many
        // items each should hold, so a half-written hour is detectable rather than looking like a
        // thin market. Deribit adds chains over time, so a hardcoded list of underlyings in the
        // exporter would silently skip any new one.
        writeManifest(table, snapshot, expiresAt, byUnderlying, total);

        String summary = "DERIBIT SNAPSHOT %s: %,d items across %d underlyings"
                .formatted(snapshot, total, byUnderlying.size());
        log(context, summary);
        return summary;
    }

    /** True when this hour's MANIFEST exists, which is written last and only on success. */
    private static boolean alreadyRecorded(String table, Instant snapshot) {
        GetItemResponse response = DYNAMO.getItem(GetItemRequest.builder()
                .tableName(table)
                .key(Map.of("snapshot_underlying", string(snapshot + "#MANIFEST"),
                        "instrument_name", string("MANIFEST")))
                .projectionExpression("item_count")
                .build());
        return response.hasItem() && !response.item().isEmpty();
    }

    /** One item per hour describing what that hour should contain. */
    private static void writeManifest(String table, Instant snapshot, long expiresAt,
                                      Map<String, Integer> byUnderlying, int total) {
        StringBuilder counts = new StringBuilder();
        byUnderlying.forEach((underlying, count) -> {
            if (!counts.isEmpty()) {
                counts.append(',');
            }
            counts.append(underlying).append('=').append(count);
        });
        Map<String, AttributeValue> manifest = new HashMap<>();
        manifest.put("snapshot_underlying", string(snapshot + "#MANIFEST"));
        manifest.put("instrument_name", string("MANIFEST"));
        manifest.put("snapshot_time", string(snapshot.toString()));
        manifest.put("underlying_counts", string(counts.toString()));
        manifest.put("item_count", number(BigDecimal.valueOf(total)));
        manifest.put("expires_at", number(BigDecimal.valueOf(expiresAt)));
        write(table, List.of(manifest));
    }

    /** Maps one book-summary response into DynamoDB items, dropping rows that cannot be trusted. */
    private static List<Map<String, AttributeValue>> toItems(
            JsonNode book, Map<String, Long> expiries, Instant snapshot, long expiresAt) {
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        for (JsonNode row : book) {
            String name = row.get("instrument_name").asText();
            Long expiry = expiries.get(name);
            String[] parts = name.split("-");
            if (expiry == null || parts.length != 4) {
                // Present in the book but absent from the instrument list: skipped rather than stored
                // with a guessed expiry.
                continue;
            }
            JsonNode mark = row.get("mark_price");
            if (mark == null || mark.isNull()) {
                continue;  // mark is the only price that must exist; without it the row is useless
            }

            String underlying = parts[0];
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("snapshot_underlying", string(snapshot + "#" + underlying));
            item.put("instrument_name", string(name));
            item.put("snapshot_time", string(snapshot.toString()));
            item.put("underlying", string(underlying));
            item.put("quote_currency", string(row.path("quote_currency").asText("?")));
            item.put("expiry_time", number(BigDecimal.valueOf(expiry)));
            // Deribit writes a decimal point as 'd' in instrument names, so XRP_USDC-25DEC26-1d05-P
            // has strike 1.05. This affects 720 of 2,654 USDC instruments - every sub-$10 underlying -
            // and none of the BTC or ETH chains, which is why it survives a test against majors alone.
            item.put("strike", number(new BigDecimal(parts[2].replace('d', '.'))));
            item.put("option_type", string(parts[3]));
            item.put("mark_price", number(mark.decimalValue()));
            item.put("expires_at", number(BigDecimal.valueOf(expiresAt)));

            // An absent bid or ask is an empty side, which is a real market state. DynamoDB has no
            // column to fill, so the attribute is simply omitted - and omitting it is also what keeps
            // the item under the 1 KB write unit.
            putIfPresent(item, "bid_price", row.get("bid_price"));
            putIfPresent(item, "ask_price", row.get("ask_price"));
            putIfPresent(item, "mark_iv", row.get("mark_iv"));
            putIfPresent(item, "underlying_price", row.get("underlying_price"));
            putIfPresent(item, "index_price", row.get("estimated_delivery_price"));
            putIfPresent(item, "open_interest", row.get("open_interest"));
            putIfPresent(item, "volume_24h", row.get("volume"));
            items.add(item);
        }
        return items;
    }

    /**
     * Writes every item, retrying whatever DynamoDB declines to take.
     *
     * <p>BatchWriteItem does not fail when it is throttled - it succeeds and hands back the items it
     * did not write in {@code UnprocessedItems}. Ignoring that field is the classic way to lose rows
     * from a batch load without any error at all, so the unprocessed remainder is retried with
     * backoff until it drains or the attempts run out.
     */
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

    /**
     * One Deribit call, retried until {@link #HTTP_RETRY_HORIZON_SECONDS} is spent.
     *
     * <p>Retries on the horizon rather than on an attempt count: what matters is outlasting the
     * outage, and four fast attempts against a DNS failure spend 14 seconds and give up while the
     * network is still down.
     */
    private JsonNode call(String path) {
        Instant callDeadline = Instant.now().plusSeconds(HTTP_RETRY_HORIZON_SECONDS);
        Instant deadline = callDeadline.isBefore(retryBudgetEnd) ? callDeadline : retryBudgetEnd;
        RuntimeException last = null;
        for (int attempt = 1; ; attempt++) {
            try {
                HttpResponse<String> response = HTTP.send(
                        HttpRequest.newBuilder(URI.create(API + path))
                                .timeout(Duration.ofSeconds(60)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("HTTP " + response.statusCode() + " for " + path);
                }
                JsonNode result = MAPPER.readTree(response.body()).get("result");
                if (result == null || result.isNull()) {
                    throw new IllegalStateException("no result field for " + path);
                }
                return result;
            } catch (java.io.IOException | IllegalStateException e) {
                last = e instanceof IllegalStateException illegal
                        ? illegal : new IllegalStateException("Request failed: " + path, e);
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

    private static void putIfPresent(Map<String, AttributeValue> item, String key, JsonNode value) {
        if (value != null && !value.isNull()) {
            item.put(key, number(value.decimalValue()));
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
