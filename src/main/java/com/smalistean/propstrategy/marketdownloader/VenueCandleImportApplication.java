package com.smalistean.propstrategy.marketdownloader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.DatabaseMigrator;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Imports daily candles from Bybit and dYdX so basis drift can be charged on the cross-venue
 * funding spread.
 *
 * <p>Probed before writing:
 * <ul>
 *   <li><b>Bybit</b> {@code /v5/market/kline} returns 1,000 candles per page and pages backwards via
 *       {@code end}; daily reaches 2020-03-25. Rows are positional arrays,
 *       {@code [startMs, open, high, low, close, volume, turnover]}, and are returned NEWEST FIRST.</li>
 *   <li><b>dYdX</b> {@code /v4/candles} returns 1,000 candles, which at daily resolution already
 *       covers its entire history from 2023-10 — one page per market, no pagination needed.</li>
 * </ul>
 *
 * <p>dYdX also publishes {@code orderbookMidPriceClose}. It is stored because on a thin market the
 * last traded price can sit far from the book, and a stale trade would appear in the results as
 * basis divergence that no one could have executed against. Bybit publishes no equivalent, so the
 * column is null there.
 */
public final class VenueCandleImportApplication {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MIN_REQUEST_INTERVAL_MS = Long.getLong("venueIntervalMs", 220L);
    private static final Object THROTTLE = new Object();
    private static long nextRequestAt = 0L;
    /** Same guard as the funding importer: a bad timestamp lands in 1970 rather than failing. */
    private static final Instant PLAUSIBLE_FROM = Instant.parse("2016-01-01T00:00:00Z");

    private record Candle(Instant open, BigDecimal o, BigDecimal h, BigDecimal l, BigDecimal c,
                          BigDecimal mid, BigDecimal volume) { }

    private VenueCandleImportApplication() {
    }

    public static void main(String[] args) throws Exception {
        Instant floor = Instant.parse(System.getProperty("candleFrom", "2020-01-01T00:00:00Z"));
        List<String> only = List.of(System.getProperty("venues", "bybit,dydx").split(","));

        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        DatabaseMigrator.migrate(database);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL).build();

        if (only.contains("bybit")) {
            List<String> symbols = new ArrayList<>();
            for (JsonNode row : get(client, "https://api.bybit.com/v5/market/tickers?category=linear")
                    .path("result").path("list")) {
                symbols.add(row.get("symbol").asText());
            }
            run(client, database, "bybit", symbols, floor);
        }
        if (only.contains("dydx")) {
            List<String> symbols = new ArrayList<>();
            get(client, "https://indexer.dydx.trade/v4/perpetualMarkets").path("markets")
                    .fieldNames().forEachRemaining(symbols::add);
            run(client, database, "dydx", symbols, floor);
        }
    }

    private static void run(HttpClient client, DatabaseConfig database, String venue,
                            List<String> symbols, Instant floor) {
        String table = venue + "_perp_kline";
        String upsert = "INSERT INTO " + table + " (venue_symbol, base, interval, open_time,"
                + " open_price, high_price, low_price, close_price, mid_price, base_volume)"
                + " VALUES (?,?,'1d',?,?,?,?,?,?,?)"
                + " ON CONFLICT (venue_symbol, interval, open_time) DO UPDATE SET"
                + " close_price = EXCLUDED.close_price, mid_price = EXCLUDED.mid_price,"
                + " base_volume = EXCLUDED.base_volume, updated_at = now()";
        long began = System.nanoTime();
        int rows = 0;
        int failed = 0;
        System.out.printf("%n=== %s: %d perpetuals ===%n", venue, symbols.size());
        for (String symbol : symbols) {
            try {
                rows += importSymbol(client, database, venue, symbol, upsert, floor);
            } catch (RuntimeException e) {
                // Symbol and cause, not just a count. Six dYdX markets failed silently on the first
                // run and identifying them meant diffing the venue's market list against the table.
                System.out.printf("!! %s %s: %s%n", venue, symbol, e.getMessage());
                failed++;
            }
        }
        System.out.printf("%s CANDLES DONE: %,d rows, %d symbols, %d failed, %d min%n",
                venue, rows, symbols.size(), failed, (System.nanoTime() - began) / 60_000_000_000L);
    }

    private static int importSymbol(HttpClient client, DatabaseConfig database, String venue,
                                    String symbol, String upsert, Instant floor) {
        int written = 0;
        Long cursor = null;
        try (Connection connection = DriverManager.getConnection(
                database.url(), database.user(), database.password())) {
            connection.setAutoCommit(false);
            for (int page = 0; page < 20; page++) {
                List<Candle> candles = "bybit".equals(venue)
                        ? bybit(client, symbol, cursor) : dydx(client, symbol);
                if (candles.isEmpty()) {
                    break;
                }
                Instant oldest = candles.get(0).open();
                try (PreparedStatement statement = connection.prepareStatement(upsert)) {
                    for (Candle candle : candles) {
                        if (candle.open().isBefore(oldest)) {
                            oldest = candle.open();
                        }
                        statement.setString(1, symbol);
                        statement.setString(2, base(venue, symbol));
                        statement.setObject(3, candle.open().atOffset(ZoneOffset.UTC));
                        statement.setBigDecimal(4, candle.o());
                        statement.setBigDecimal(5, candle.h());
                        statement.setBigDecimal(6, candle.l());
                        statement.setBigDecimal(7, candle.c());
                        if (candle.mid() == null) {
                            statement.setNull(8, Types.NUMERIC);
                        } else {
                            statement.setBigDecimal(8, candle.mid());
                        }
                        statement.setBigDecimal(9, candle.volume());
                        statement.addBatch();
                    }
                    written += statement.executeBatch().length;
                }
                connection.commit();
                // dYdX needs no pagination: 1,000 daily candles already spans its whole history.
                if ("dydx".equals(venue) || candles.size() < 1000 || oldest.isBefore(floor)) {
                    break;
                }
                cursor = oldest.toEpochMilli() - 1;
            }
            return written;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed storing " + venue + " " + symbol, e);
        }
    }

    /** Bybit rows are positional arrays, newest first: [startMs, open, high, low, close, volume, turnover]. */
    private static List<Candle> bybit(HttpClient client, String symbol, Long endCursor) {
        JsonNode list = get(client, "https://api.bybit.com/v5/market/kline?category=linear&symbol="
                + symbol + "&interval=D&limit=1000" + (endCursor == null ? "" : "&end=" + endCursor))
                .path("result").path("list");
        List<Candle> out = new ArrayList<>();
        for (JsonNode row : list) {
            if (row.size() < 6) {
                continue;
            }
            try {
                Instant open = Instant.ofEpochMilli(Long.parseLong(row.get(0).asText()));
                if (open.isBefore(PLAUSIBLE_FROM)) {
                    continue;
                }
                out.add(new Candle(open, dec(row.get(1)), dec(row.get(2)), dec(row.get(3)),
                        dec(row.get(4)), null, dec(row.get(5))));
            } catch (NumberFormatException e) {
                // One malformed candle must not cost a symbol its history.
            }
        }
        return out;
    }

    private static List<Candle> dydx(HttpClient client, String symbol) {
        JsonNode candles = get(client, "https://indexer.dydx.trade/v4/candles/perpetualMarkets/"
                + symbol + "?resolution=1DAY&limit=1000").path("candles");
        List<Candle> out = new ArrayList<>();
        for (JsonNode row : candles) {
            try {
                JsonNode mid = row.get("orderbookMidPriceClose");
                out.add(new Candle(Instant.parse(row.get("startedAt").asText()),
                        dec(row.get("open")), dec(row.get("high")), dec(row.get("low")),
                        dec(row.get("close")),
                        mid == null || mid.isNull() ? null : dec(mid),
                        dec(row.get("baseTokenVolume"))));
            } catch (RuntimeException e) {
                // Same rule: skip the row, keep the symbol.
            }
        }
        return out;
    }

    private static BigDecimal dec(JsonNode node) {
        return new BigDecimal(node.asText());
    }

    /** Mirrors normalise_perp_base in V14 so candles join to funding on the same key. */
    private static String base(String venue, String symbol) {
        String raw = "dydx".equals(venue) ? symbol.split(",")[0].split("-")[0]
                : symbol.endsWith("USDT") || symbol.endsWith("USDC")
                        ? symbol.substring(0, symbol.length() - 4) : symbol;
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

    private static JsonNode get(HttpClient client, String url) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            throttle();
            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(45))
                                .header("User-Agent", "prop-strategy-research").GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("HTTP " + response.statusCode() + " for " + url);
                }
                return MAPPER.readTree(response.body());
            } catch (java.io.IOException | IllegalStateException e) {
                last = e instanceof IllegalStateException illegal
                        ? illegal : new IllegalStateException("Request failed: " + url, e);
                sleep(1_000L * attempt * attempt);
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
