package com.smalistean.propstrategy.marketdownloader;

import com.fasterxml.jackson.databind.JsonNode;
import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.DatabaseMigrator;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Imports Hyperliquid perpetual candles into {@code hyperliquid_perp_kline}.
 *
 * <p>Needed to measure basis drift between Hyperliquid and Binance marks - the largest unmeasured
 * cost in the cross-venue funding trade. See {@code V11__create_hyperliquid_kline.sql}.
 *
 * <h2>The endpoint serves only the most recent 5,000 candles — it cannot be paged backwards</h2>
 * Probed directly, and this is the constraint that decides the interval:
 * <ul>
 *   <li>A window entirely in the past ({@code 2023-06-01..2023-07-01}) returns <b>0 rows</b>.</li>
 *   <li>{@code startTime=2023-05-01, endTime=now} at 1h returns 5,003 rows, all of them from
 *       2026-01-16 onward. {@code startTime} is effectively ignored once the cap binds.</li>
 * </ul>
 * So older data is not merely unpaginated, it is <b>not served</b>, and walking a cursor forward
 * collects one page and stops. A first run at 1h did exactly that: every coin got the same recent
 * 208 days and nothing before.
 *
 * <p>The cap is on candle <b>count</b>, so a coarser interval reaches further: 4h reaches 2024-05-01
 * (5,001 rows, still capped), while <b>12h and 1d cover the whole history from 2023-04-30</b> in
 * 2,401 and 1,201 rows respectively - under the cap, so nothing is lost. Daily is the default because
 * it spans everything and matches how {@code CarryHarvestApplication} already computes basis from
 * daily closes over a weekly hold.
 *
 * <p>1h remains available for roughly the last 208 days if intraday basis is ever needed; the
 * {@code interval} column keeps the two apart.
 *
 * <h2>Survivorship, unchanged and still disclosed</h2>
 * The universe comes from {@code meta}, which lists currently-listed coins only. Every coin here
 * survived to today. Adding prices does not fix that; it is a property of the venue's API, and any
 * result computed from these tables is conditioned on survival.
 */
public final class HyperliquidCandleImportApplication {

    /** Probed, not assumed: the endpoint caps a response at this many candles. */
    private static final int PAGE_LIMIT = 5_000;
    private static final String DEFAULT_START = "2023-05-01T00:00:00Z";

    private static final String UPSERT = """
            INSERT INTO hyperliquid_perp_kline (coin, interval, open_time, close_time, open_price,
                                           high_price, low_price, close_price, base_volume, trade_count)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (coin, interval, open_time) DO UPDATE SET
                close_price = EXCLUDED.close_price, high_price = EXCLUDED.high_price,
                low_price = EXCLUDED.low_price, base_volume = EXCLUDED.base_volume,
                trade_count = EXCLUDED.trade_count, updated_at = now()
            """;

    /** Candles dropped for an unparseable field, reported at the end so they cannot pass unnoticed. */
    private static final AtomicInteger SKIPPED = new AtomicInteger();

    private HyperliquidCandleImportApplication() {
    }

    public static void main(String[] args) throws Exception {
        // 1d, not 1h: the venue documents "only the most recent 5000 candles are available", which at
        // hourly resolution is 208 days. Daily needs 1,201 rows for the whole history and stays under
        // the cap.
        String interval = System.getProperty("hlCandleInterval", "1d");
        Instant start = Instant.parse(System.getProperty("hlCandleStart", DEFAULT_START));
        Instant end = Instant.parse(System.getProperty("hlCandleEnd", Instant.now().toString()));
        int threads = Integer.getInteger("hlCandleThreads", 3);

        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        DatabaseMigrator.migrate(database);
        HyperliquidClient client = new HyperliquidClient();

        List<String> coins = new ArrayList<>();
        for (JsonNode asset : client.post("{\"type\":\"meta\"}").get("universe")) {
            coins.add(asset.get("name").asText());
        }
        coins.sort(null);
        System.out.printf("universe: %d Hyperliquid perpetuals, interval %s%n", coins.size(), interval);

        long began = System.nanoTime();
        AtomicInteger rowsTotal = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (String coin : coins) {
            pool.submit(() -> {
                try {
                    Instant from = latestStored(database, coin, interval)
                            .map(t -> t.plusSeconds(1)).orElse(start);
                    AtomicInteger rows = new AtomicInteger();
                    if (from.isBefore(end)) {
                        importCoin(client, database, coin, interval, from, end, rows);
                    }
                    rowsTotal.addAndGet(rows.get());
                    int n = done.incrementAndGet();
                    if (rows.get() > 0 || n % 25 == 0) {
                        System.out.printf("[%d/%d] %-12s %,7d candles%n", n, coins.size(), coin, rows.get());
                    }
                } catch (RuntimeException e) {
                    System.out.printf("!! %s failed: %s%n", coin, e.getMessage());
                    done.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        if (!pool.awaitTermination(6, TimeUnit.HOURS)) {
            throw new IllegalStateException("Timed out importing Hyperliquid candles");
        }
        System.out.printf("HYPERLIQUID CANDLE IMPORT DONE: %,d rows across %d coins in %d minutes%n",
                rowsTotal.get(), coins.size(), (System.nanoTime() - began) / 60_000_000_000L);
        if (SKIPPED.get() > 0) {
            System.out.printf("  %,d candles skipped for an unparseable field%n", SKIPPED.get());
        }
        // Coverage is asserted, not assumed. If the 5,000-candle cap silently binds at this interval,
        // every coin quietly starts late and a basis measurement would run on a truncated window
        // while looking complete - which is exactly what the first 1h run did.
        reportCoverage(database, interval, start);
    }

    private static void importCoin(HyperliquidClient client, DatabaseConfig database, String coin,
                                   String interval, Instant from, Instant end, AtomicInteger written) {
        Instant cursor = from;
        try (Connection connection = DriverManager.getConnection(
                database.url(), database.user(), database.password())) {
            connection.setAutoCommit(false);
            while (cursor.isBefore(end)) {
                JsonNode page = client.post("""
                        {"type":"candleSnapshot","req":{"coin":"%s","interval":"%s","startTime":%d,"endTime":%d}}"""
                        .formatted(coin, interval, cursor.toEpochMilli(), end.toEpochMilli()));
                if (!page.isArray() || page.isEmpty()) {
                    break;
                }
                Instant last = null;
                try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
                    for (JsonNode candle : page) {
                        Instant open = Instant.ofEpochMilli(candle.get("t").asLong());
                        last = open;
                        BigDecimal[] prices = decimals(candle, "o", "h", "l", "c", "v");
                        if (prices == null) {
                            // One malformed candle must not cost a coin its history, the same failure
                            // that truncated nine coins in the funding import.
                            SKIPPED.incrementAndGet();
                            continue;
                        }
                        statement.setString(1, coin);
                        statement.setString(2, interval);
                        statement.setObject(3, open.atOffset(ZoneOffset.UTC));
                        statement.setObject(4, Instant.ofEpochMilli(candle.get("T").asLong())
                                .atOffset(ZoneOffset.UTC));
                        statement.setBigDecimal(5, prices[0]);
                        statement.setBigDecimal(6, prices[1]);
                        statement.setBigDecimal(7, prices[2]);
                        statement.setBigDecimal(8, prices[3]);
                        statement.setBigDecimal(9, prices[4]);
                        statement.setInt(10, candle.path("n").asInt());
                        statement.addBatch();
                    }
                    written.addAndGet(statement.executeBatch().length);
                }
                connection.commit();
                // Advance from the newest candle received: the 5,000-row cap truncates any longer
                // window, and a fixed step would skip whatever the cap cut off.
                if (page.size() < PAGE_LIMIT || last == null) {
                    break;
                }
                cursor = last.plusSeconds(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to store candles for " + coin, e);
        }
    }

    /**
     * Reports how far back the stored history actually reaches, against what was requested.
     *
     * <p>Exists because the failure it detects is invisible otherwise. The venue serves only the most
     * recent 5,000 candles and ignores {@code startTime} once that binds, so an interval too fine for
     * the requested span yields a full-looking table that begins months late for every coin.
     */
    private static void reportCoverage(DatabaseConfig database, String interval, Instant requested) {
        try (Connection connection = DriverManager.getConnection(
                database.url(), database.user(), database.password());
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT count(*), min(first), max(first)
                     FROM (SELECT coin, min(open_time) AS first FROM hyperliquid_perp_kline
                           WHERE interval = ? GROUP BY coin) per_coin
                     """)) {
            statement.setString(1, interval);
            try (ResultSet results = statement.executeQuery()) {
                if (results.next() && results.getTimestamp(2) != null) {
                    Instant earliest = results.getTimestamp(2).toInstant();
                    Instant latestStart = results.getTimestamp(3).toInstant();
                    System.out.printf("  coverage: %d coins, earliest start %s, latest start %s%n",
                            results.getInt(1), earliest, latestStart);
                    if (earliest.isAfter(requested.plus(java.time.Duration.ofDays(7)))) {
                        System.out.printf("  WARNING: no coin reaches %s. The 5,000-candle cap is "
                                + "binding at interval %s - use a coarser one.%n", requested, interval);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to report coverage", e);
        }
    }

    /** Returns null when any field is absent or not a number, so the caller can skip one candle. */
    private static BigDecimal[] decimals(JsonNode candle, String... fields) {
        BigDecimal[] values = new BigDecimal[fields.length];
        for (int i = 0; i < fields.length; i++) {
            JsonNode value = candle.get(fields[i]);
            if (value == null || value.isNull()) {
                return null;
            }
            try {
                values[i] = new BigDecimal(value.asText());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return values;
    }

    private static java.util.Optional<Instant> latestStored(DatabaseConfig database, String coin,
                                                            String interval) {
        try (Connection connection = DriverManager.getConnection(
                database.url(), database.user(), database.password());
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT max(open_time) FROM hyperliquid_perp_kline WHERE coin = ? AND interval = ?")) {
            statement.setString(1, coin);
            statement.setString(2, interval);
            try (ResultSet results = statement.executeQuery()) {
                if (results.next()) {
                    java.sql.Timestamp stamp = results.getTimestamp(1);
                    return java.util.Optional.ofNullable(stamp).map(java.sql.Timestamp::toInstant);
                }
                return java.util.Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read stored candles for " + coin, e);
        }
    }
}
