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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Imports Hyperliquid perpetual funding history into {@code hyperliquid_perp_funding_rate}.
 *
 * <p>Motivated by the cross-venue funding spread: Hyperliquid and Binance both pay funding on the
 * same coin, computed by different formulas on different schedules, so the two can diverge without
 * either being wrong. A position long on one venue and short on the other is delta-neutral in the
 * <em>same</em> asset, which is the property {@code CARRY_PREREGISTRATION.md} relied on and
 * {@code CARRY_PERP_HEDGE_PREREGISTRATION.md} gives up by hedging with BTC beta.
 *
 * <h2>Pagination</h2>
 * {@code fundingHistory} returns at most <b>500 rows</b> regardless of the window requested - probed
 * directly: a 30-day, 60-day and 120-day request all return 500 rows spanning 499 hours. Since
 * funding is hourly, 500 rows is under 21 days, so the window must be advanced from the last row
 * received rather than by a fixed step. A fixed step would silently skip hours.
 *
 * <h2>Survivorship, disclosed</h2>
 * The universe comes from {@code meta}, which lists <b>currently listed</b> coins only. Unlike the
 * Binance import - where the archive directory listing reveals delisted names such as FTTUSDT and
 * LUNAUSDT - there is no equivalent historical listing here, so delisted Hyperliquid perps are
 * absent. Any result computed from this table is therefore conditioned on survival to today. That
 * limitation belongs in the pre-registration, not in a comment discovered later.
 */
public final class HyperliquidFundingImportApplication {

    private static final String INFO = "https://api.hyperliquid.xyz/info";
    /** Probed, not assumed: the endpoint caps a response at this many rows whatever the window. */
    private static final int PAGE_LIMIT = 500;
    /** Earliest funding row the API serves for BTC, found by binary search. */
    private static final String DEFAULT_START = "2023-05-01T00:00:00Z";

    /**
     * Minimum interval between requests, enforced across all threads.
     *
     * <p>The info endpoint is weight-limited per IP, not per connection, so raising thread count
     * without pacing simply converts throughput into HTTP 429s: a first run at four unpaced threads
     * completed 114 of 232 coins and failed the rest. Pacing globally is what makes the thread count
     * safe to change.
     */
    private static final long MIN_REQUEST_INTERVAL_MS =
            Long.getLong("hlFundingIntervalMs", 900L);

    private static final Object THROTTLE = new Object();
    private static long nextRequestAt = 0L;

    /** Rows dropped for an unparseable rate. Reported at the end so they cannot pass unnoticed. */
    private static final AtomicInteger SKIPPED = new AtomicInteger();

    private static final String UPSERT = """
            INSERT INTO hyperliquid_perp_funding_rate (coin, funding_time, funding_rate, premium)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (coin, funding_time)
            DO UPDATE SET funding_rate = EXCLUDED.funding_rate,
                          premium = EXCLUDED.premium,
                          updated_at = now()
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HyperliquidFundingImportApplication() {
    }

    public static void main(String[] args) throws Exception {
        Instant start = Instant.parse(System.getProperty("hlFundingStart", DEFAULT_START));
        Instant end = Instant.parse(System.getProperty("hlFundingEnd", Instant.now().toString()));
        // Deliberately modest. The info endpoint is weight-limited per IP and this is a one-off
        // backfill, not a latency-sensitive path; being throttled mid-import would leave gaps that
        // look like genuine absences of funding.
        int threads = Integer.getInteger("hlFundingThreads", 3);

        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        DatabaseMigrator.migrate(database);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL).build();

        List<String> coins = listCoins(client);
        System.out.printf("universe: %d Hyperliquid perpetuals (currently listed only)%n", coins.size());

        long began = System.nanoTime();
        AtomicInteger rowsTotal = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (String coin : coins) {
            pool.submit(() -> {
                try {
                    // Resume from what is already stored so a re-run costs one query per coin rather
                    // than re-walking three years of hourly pages.
                    Instant from = latestStored(database, coin).map(t -> t.plusSeconds(1)).orElse(start);
                    // Counted as pages commit rather than on clean completion, so a coin that fails
                    // partway still reports what it actually wrote. The first run logged 123,669
                    // rows while the table held 841,169, because committed pages of failed coins
                    // were dropped from the total.
                    AtomicInteger rows = new AtomicInteger();
                    if (from.isBefore(end)) {
                        importCoin(client, database, coin, from, end, rows);
                    }
                    rowsTotal.addAndGet(rows.get());
                    int n = done.incrementAndGet();
                    if (rows.get() > 0 || n % 25 == 0) {
                        System.out.printf("[%d/%d] %-12s %,7d rows%n", n, coins.size(), coin, rows.get());
                    }
                } catch (RuntimeException e) {
                    // Left unrecorded so the next run retries it: a transient failure must not be
                    // baked into the dataset as an absence of funding.
                    System.out.printf("!! %s failed: %s%n", coin, e.getMessage());
                    done.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        if (!pool.awaitTermination(6, TimeUnit.HOURS)) {
            throw new IllegalStateException("Timed out importing Hyperliquid funding");
        }
        System.out.printf("HYPERLIQUID FUNDING IMPORT DONE: %,d rows across %d coins in %d minutes%n",
                rowsTotal.get(), coins.size(), (System.nanoTime() - began) / 60_000_000_000L);
        if (SKIPPED.get() > 0) {
            System.out.printf("  %,d rows skipped for an unparseable rate%n", SKIPPED.get());
        }
    }

    private static void importCoin(HttpClient client, DatabaseConfig database, String coin,
                                   Instant from, Instant end, AtomicInteger written) {
        Instant cursor = from;
        try (Connection connection = DriverManager.getConnection(
                database.url(), database.user(), database.password())) {
            connection.setAutoCommit(false);
            while (cursor.isBefore(end)) {
                JsonNode page = post(client, """
                        {"type":"fundingHistory","coin":"%s","startTime":%d,"endTime":%d}"""
                        .formatted(coin, cursor.toEpochMilli(), end.toEpochMilli()));
                if (!page.isArray() || page.isEmpty()) {
                    break;
                }
                Instant last = null;
                try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
                    for (JsonNode row : page) {
                        Instant time = Instant.ofEpochMilli(row.get("time").asLong());
                        // The API occasionally returns a non-numeric rate - FRIEND aborted a whole
                        // run on one such value. A single unparseable hour must not cost a coin its
                        // entire history, so the row is skipped and counted. The cursor still
                        // advances past it, otherwise the import would loop on it forever.
                        BigDecimal rate = decimalOrNull(row.get("fundingRate"));
                        last = time;
                        if (rate == null) {
                            SKIPPED.incrementAndGet();
                            continue;
                        }
                        statement.setString(1, coin);
                        statement.setObject(2, time.atOffset(java.time.ZoneOffset.UTC));
                        statement.setBigDecimal(3, rate);
                        BigDecimal premium = decimalOrNull(row.get("premium"));
                        if (premium == null) {
                            statement.setNull(4, java.sql.Types.NUMERIC);
                        } else {
                            statement.setBigDecimal(4, premium);
                        }
                        statement.addBatch();
                    }
                    written.addAndGet(statement.executeBatch().length);
                }
                connection.commit();
                // Advance from the newest row actually received. The response is capped at 500 rows,
                // so a fixed step would skip hours whenever the cap truncates the window.
                if (page.size() < PAGE_LIMIT || last == null) {
                    break;
                }
                cursor = last.plusSeconds(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to store funding for " + coin, e);
        }
    }

    private static java.util.Optional<Instant> latestStored(DatabaseConfig database, String coin) {
        try (Connection connection = DriverManager.getConnection(
                database.url(), database.user(), database.password());
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT max(funding_time) FROM hyperliquid_perp_funding_rate WHERE coin = ?")) {
            statement.setString(1, coin);
            try (ResultSet results = statement.executeQuery()) {
                if (results.next()) {
                    java.sql.Timestamp stamp = results.getTimestamp(1);
                    return java.util.Optional.ofNullable(stamp).map(java.sql.Timestamp::toInstant);
                }
                return java.util.Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read stored funding for " + coin, e);
        }
    }

    private static List<String> listCoins(HttpClient client) {
        JsonNode meta = post(client, "{\"type\":\"meta\"}");
        List<String> coins = new ArrayList<>();
        for (JsonNode asset : meta.get("universe")) {
            // Delisted markets stay in the universe flagged as delisted; they carry no live funding
            // but their history is still worth having, so they are kept rather than filtered.
            coins.add(asset.get("name").asText());
        }
        coins.sort(null);
        return coins;
    }

    private static JsonNode post(HttpClient client, String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(INFO))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 6; attempt++) {
            throttle();
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 429 || response.statusCode() >= 500) {
                    // Weight-limited or transient: back off rather than treating it as no data.
                    throw new IllegalStateException("HTTP " + response.statusCode());
                }
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
                }
                return MAPPER.readTree(response.body());
            } catch (java.io.IOException | IllegalStateException e) {
                last = e instanceof IllegalStateException illegal
                        ? illegal : new IllegalStateException("Request failed", e);
                // Reaches ~72s by the final attempt. The first run used 0.5s..8s, which is ample for
                // a dropped connection and far too short for a weight limit that persists while
                // other threads keep consuming the same budget.
                sleep(2_000L * attempt * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted", e);
            }
        }
        throw last;
    }

    /**
     * Parses a rate, returning null when the value is absent or not a number.
     *
     * <p>Kept separate so a malformed value is a property of one row rather than a failure of the
     * import. Skipped rows are counted and reported at the end: silently dropping them would let a
     * systematic problem look like a coin that simply paid no funding.
     */
    private static BigDecimal decimalOrNull(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Spaces requests across every thread, since the endpoint's limit is per IP rather than per connection. */
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
