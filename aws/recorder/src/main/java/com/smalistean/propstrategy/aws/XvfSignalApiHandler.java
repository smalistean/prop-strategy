package com.smalistean.propstrategy.aws;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only HTTP endpoint serving the current frozen XVF book, for the static web page.
 *
 * <h2>Function URL, not API Gateway</h2>
 * One route, one method, no auth, no rate plan, no usage keys. A Function URL is free where API
 * Gateway is $1 per million requests plus a REST resource to keep in sync with this handler.
 *
 * <h2>Why a separate handler from the ranker</h2>
 * Different trust boundary. {@link XvfSignalHandler} writes and is invoked only by EventBridge; this
 * is reachable by anyone with the URL, so it holds a read-only DynamoDB policy and can do nothing but
 * return a book that is already public information. Sharing one function would have given an
 * internet-facing endpoint write access to the signal table.
 *
 * <h2>Response shape</h2>
 * <pre>
 * { "signalRunId": "...", "cutoffUtc": "...", "generatedAt": "...", "status": "READY",
 *   "lookbackDays": 7, "positions": 20, "legsConsidered": 1843, "venues": "binance,bybit,hyperliquid",
 *   "book": [ { "rank": 1, "base": "COW", "shortVenue": ..., "spreadAnnualPct": 229.0, ... } ] }
 * </pre>
 *
 * <p>Returns {@code status: "NONE"} with an empty book when no run has been written, rather than an
 * error - an empty table is a normal state before the first ranking, and the page should say so
 * rather than show a failure.
 */
public final class XvfSignalApiHandler
        implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DynamoDbClient DYNAMO = DynamoDbClient.builder()
            .httpClient(UrlConnectionHttpClient.builder().build()).build();

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        String table = System.getenv("SIGNAL_TABLE");
        if (table == null || table.isBlank()) {
            throw new IllegalStateException("SIGNAL_TABLE is not set");
        }

        GetItemResponse pointer = DYNAMO.getItem(GetItemRequest.builder()
                .tableName(table)
                .key(Map.of("pk", s("LATEST"), "sk", s("META")))
                .build());
        if (!pointer.hasItem() || pointer.item().isEmpty()) {
            return respond(Map.of("status", "NONE", "book", List.of()));
        }
        String runId = pointer.item().get("signal_run_id").s();

        QueryResponse run = DYNAMO.query(QueryRequest.builder()
                .tableName(table)
                .keyConditionExpression("pk = :k")
                .expressionAttributeValues(Map.of(":k", s("RUN#" + runId)))
                .build());

        Map<String, Object> body = new HashMap<>();
        body.put("signalRunId", runId);
        List<Map<String, Object>> book = new ArrayList<>();
        for (Map<String, AttributeValue> item : run.items()) {
            if ("META".equals(item.get("sk").s())) {
                body.put("cutoffUtc", str(item, "cutoff_utc"));
                body.put("generatedAt", str(item, "generated_at"));
                body.put("status", str(item, "status"));
                body.put("venues", str(item, "venues"));
                body.put("lookbackDays", num(item, "lookback_days"));
                body.put("positions", num(item, "positions"));
                body.put("legsConsidered", num(item, "legs_considered"));
                body.put("minSpreadPct", num(item, "min_spread_pct"));
                continue;
            }
            Map<String, Object> row = new HashMap<>();
            row.put("rank", num(item, "rank"));
            row.put("base", str(item, "base"));
            row.put("shortVenue", str(item, "short_venue"));
            row.put("shortSymbol", str(item, "short_symbol"));
            row.put("shortRate", num(item, "short_rate"));
            row.put("longVenue", str(item, "long_venue"));
            row.put("longSymbol", str(item, "long_symbol"));
            row.put("longRate", num(item, "long_rate"));
            row.put("spreadAnnualPct", num(item, "spread_annual_pct"));
            row.put("thinLegWeeklyVolume", num(item, "thin_leg_weekly_volume"));
            book.add(row);
        }
        book.sort(Comparator.comparingDouble(r -> ((Number) r.get("rank")).doubleValue()));
        body.put("book", book);
        return respond(body);
    }

    /**
     * Function URL envelope.
     *
     * <p>Deliberately sets NO {@code access-control-allow-origin}. The Function URL's own Cors config
     * adds one, echoing the caller's origin; a second header from here made the response carry
     * {@code *, http://...} and every browser rejects a duplicated allow-origin outright. CORS has
     * exactly one owner and it is the URL config.
     */
    private static Map<String, Object> respond(Map<String, Object> body) {
        String json;
        try {
            // `body` must be a STRING for a Function URL envelope. Returning the map here yields a
            // response whose body is the literal text of a Java map, which parses as nothing.
            json = MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise the book", e);
        }
        return Map.of(
                "statusCode", 200,
                "headers", Map.of(
                        "content-type", "application/json",
                        // 60s: the ranker runs hourly, so a browser refreshing faster than this is
                        // asking for a book that cannot have changed.
                        "cache-control", "public, max-age=60"),
                "body", json);
    }

    private static AttributeValue s(String v) {
        return AttributeValue.builder().s(v).build();
    }

    private static String str(Map<String, AttributeValue> item, String key) {
        AttributeValue v = item.get(key);
        return v == null ? null : v.s();
    }

    private static Double num(Map<String, AttributeValue> item, String key) {
        AttributeValue v = item.get(key);
        return v == null || v.n() == null ? null : Double.valueOf(v.n());
    }
}
