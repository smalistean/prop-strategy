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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Imports perpetual funding history from Binance, Bybit, OKX, Gate, Bitget, dYdX and Aster.
 *
 * <p>One class with per-venue adapters rather than near-identical importers: the differences are a
 * URL, a JSON path and a timestamp unit, and separate copies would drift apart the moment one needed
 * a fix. Not wired into {@code scripts/xvf-refresh.sh}'s daily cron - the venue list there is a
 * literal argument, not this class's default, so adding Aster here does not change what runs
 * unattended. Research-only until decided otherwise.
 *
 * <h2>What each venue actually serves — probed, not assumed</h2>
 * <table>
 *   <tr><th>Venue</th><th>Depth</th><th>Page</th><th>Trap</th></tr>
 *   <tr><td>Bybit</td><td>2023-10+</td><td>200</td><td>-</td></tr>
 *   <tr><td>dYdX</td><td>2023-12+</td><td>100</td><td>hourly funding; ISO-8601 timestamps</td></tr>
 *   <tr><td>OKX</td><td>~3 months</td><td>100</td><td>{@code realizedRate}, not {@code fundingRate}</td></tr>
 *   <tr><td>Bitget</td><td>~2 months</td><td>100</td><td>page-number paging, empty past page 2</td></tr>
 *   <tr><td>Gate</td><td>~30 days</td><td>1000 cap, 90 served</td><td><b>timestamps in SECONDS</b></td></tr>
 * </table>
 *
 * The shallow three cannot support a backtest and are imported anyway: they contribute to forward
 * collection, which is what every strategy in this project is now waiting on.
 *
 * <p><b>Gate's seconds.</b> Every other venue here reports milliseconds. Multiplying by the wrong
 * factor does not fail - it files rows in 1970 or the year 58000, where they simply never join to
 * anything. The unit is part of each adapter for that reason.
 *
 * <h2>Pagination and resumption</h2>
 * Every run walks <b>backwards</b> from now, because that is the only direction all five support.
 * It stops when a page comes back empty, when it passes {@code venueFundingFrom}, or when an entire
 * page is already stored — so the first run backfills and later runs cost one or two pages per
 * symbol. Upserts make an overlapping page harmless.
 */
public final class VenueFundingImportApplication {

    private record Payment(Instant time, BigDecimal rate) { }

    /**
     * @param pageUrl symbol and a millisecond cursor to a request URL for the page ending at it
     * @param parse   venue response to payments, newest first
     */
    private record Venue(String name, String table,
                         Function<HttpClient, List<String>> listSymbols,
                         java.util.function.BiFunction<String, Long, String> pageUrl,
                         Function<JsonNode, List<Payment>> parse,
                         Function<String, String> base,
                         int pageSize) { }

    /** Perpetual funding did not exist before 2016 and cannot be dated in the future. */
    private static final Instant PLAUSIBLE_FROM = Instant.parse("2016-01-01T00:00:00Z");
    private static final Instant PLAUSIBLE_TO = Instant.now().plusSeconds(86_400);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MIN_REQUEST_INTERVAL_MS = Long.getLong("venueIntervalMs", 220L);
    private static final Object THROTTLE = new Object();
    private static long nextRequestAt = 0L;

    private VenueFundingImportApplication() {
    }

    private static List<Venue> venues() {
        return List.of(
            // Binance via REST, to top up what the MONTHLY archive cannot supply.
            //
            // FundingArchiveImportApplication reaches 2020 but only publishes complete months, so
            // between month-end and publication only the sixteen symbols that also carry `Regular`
            // rows stay current. A cross-venue signal then pairs 800 legs from other venues against
            // 16 from Binance and returns an empty book with no error - which is how this gap was
            // found. Rows are written with rate_type 'REST'; perp_funding_all deduplicates per
            // (symbol, funding_time) so overlap with the archive cannot double-count.
            new Venue("binance", "binance_perp_funding_rate",
                c -> jsonList(get(c, "https://fapi.binance.com/fapi/v1/premiumIndex"), "symbol")
                        .stream().filter(s -> s.endsWith("USDT") || s.endsWith("USDC")).toList(),
                (s, cursor) -> "https://fapi.binance.com/fapi/v1/fundingRate?symbol=" + s
                        + "&limit=1000" + (cursor == null ? "" : "&endTime=" + cursor),
                j -> payments(j, "fundingTime", "fundingRate", 1),
                s -> strip(s, "USDT", "USDC"), 1000),

            new Venue("bybit", "bybit_perp_funding_rate",
                c -> jsonList(get(c, "https://api.bybit.com/v5/market/tickers?category=linear")
                        .path("result").path("list"), "symbol"),
                (s, cursor) -> "https://api.bybit.com/v5/market/funding/history?category=linear&symbol="
                        + s + "&limit=200" + (cursor == null ? "" : "&endTime=" + cursor),
                j -> payments(j.path("result").path("list"), "fundingRateTimestamp", "fundingRate", 1),
                s -> strip(s, "USDT", "USDC", "PERP"), 200),

            new Venue("okx", "okx_perp_funding_rate",
                c -> jsonList(get(c, "https://www.okx.com/api/v5/public/instruments?instType=SWAP")
                        .path("data"), "instId"),
                (s, cursor) -> "https://www.okx.com/api/v5/public/funding-rate-history?instId=" + s
                        + "&limit=100" + (cursor == null ? "" : "&after=" + cursor),
                // realizedRate is what was actually paid; fundingRate on a history row can be the
                // predicted value for the period.
                j -> payments(j.path("data"), "fundingTime", "realizedRate", 1),
                s -> s.split("-")[0], 100),

            new Venue("gate", "gate_perp_funding_rate",
                c -> jsonList(get(c, "https://api.gateio.ws/api/v4/futures/usdt/contracts"), "name"),
                (s, cursor) -> "https://api.gateio.ws/api/v4/futures/usdt/funding_rate?contract="
                        + s + "&limit=1000",
                // 1000 -> milliseconds. Gate is the only venue here reporting seconds.
                j -> payments(j, "t", "r", 1000),
                s -> s.split("_")[0], 1000),

            new Venue("bitget", "bitget_perp_funding_rate",
                c -> jsonList(get(c, "https://api.bitget.com/api/v2/mix/market/tickers?productType=USDT-FUTURES")
                        .path("data"), "symbol"),
                (s, page) -> "https://api.bitget.com/api/v2/mix/market/history-fund-rate?symbol=" + s
                        + "&productType=USDT-FUTURES&pageSize=100&pageNo=" + (page == null ? 1 : page),
                j -> payments(j.path("data"), "fundingTime", "fundingRate", 1),
                s -> strip(s, "USDT", "USDC"), 100),

            new Venue("dydx", "dydx_perp_funding_rate",
                c -> {
                    List<String> out = new ArrayList<>();
                    get(c, "https://indexer.dydx.trade/v4/perpetualMarkets").path("markets")
                            .fieldNames().forEachRemaining(out::add);
                    return out;
                },
                (s, cursor) -> "https://indexer.dydx.trade/v4/historicalFunding/" + s + "?limit=100"
                        + (cursor == null ? "" : "&effectiveBeforeOrAt="
                            + Instant.ofEpochMilli(cursor).toString()),
                j -> {
                    List<Payment> out = new ArrayList<>();
                    for (JsonNode row : j.path("historicalFunding")) {
                        out.add(new Payment(Instant.parse(row.get("effectiveAt").asText()),
                                new BigDecimal(row.get("rate").asText())));
                    }
                    return out;
                },
                // dYdX permissionless markets embed a DEX and a contract address:
                // "FARTCOIN,RAYDIUM,9BB6NF...PUMP-USD". Splitting on "-" alone returns 61 characters
                // of address instead of FARTCOIN, which would store a base that can never join to the
                // same asset on another venue. The segment before the first comma is the asset.
                s -> s.split(",")[0].split("-")[0], 100),

            // Aster: a Binance-API-shaped perpetual DEX (RESEARCH_OPTIONS.md item 1 follow-up,
            // XVF_LIVE_FINDINGS.md §12). Its instruments list carries genuine crypto AND tokenized
            // stocks (AAPL, TSLA, SKHYNIX, ...) under plain crypto-looking tickers - the same
            // collision risk as the ON Semiconductor incident, but with an explicit field to filter
            // on this time: underlyingSubType contains "STOCK" for every non-crypto listing found.
            // Filtered here, at listSymbols, so a stock can never enter funding history at all -
            // stricter than XvfExecutionApplication's requireCryptoPerp, which only refuses at
            // sizing time and would need this same check duplicated if this filter lived downstream.
            new Venue("aster", "aster_perp_funding_rate",
                c -> {
                    List<String> out = new ArrayList<>();
                    for (JsonNode s : get(c, "https://fapi.asterdex.com/fapi/v1/exchangeInfo")
                            .path("symbols")) {
                        if (!"TRADING".equals(s.path("status").asText())) {
                            continue;
                        }
                        String quote = s.path("quoteAsset").asText();
                        if (!"USDT".equals(quote) && !"USDC".equals(quote)) {
                            continue;
                        }
                        boolean stock = false;
                        for (JsonNode sub : s.path("underlyingSubType")) {
                            if ("STOCK".equals(sub.asText())) {
                                stock = true;
                                break;
                            }
                        }
                        if (!stock) {
                            out.add(s.path("symbol").asText());
                        }
                    }
                    return out;
                },
                (s, cursor) -> "https://fapi.asterdex.com/fapi/v1/fundingRate?symbol=" + s
                        + "&limit=1000" + (cursor == null ? "" : "&endTime=" + cursor),
                j -> payments(j, "fundingTime", "fundingRate", 1),
                s -> strip(s, "USDT", "USDC"), 1000));
    }

    public static void main(String[] args) throws Exception {
        Instant floor = Instant.parse(System.getProperty("venueFundingFrom", "2023-01-01T00:00:00Z"));
        List<String> only = List.of(System.getProperty("venues",
                "binance,bybit,dydx,okx,bitget,gate").split(","));

        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        DatabaseMigrator.migrate(database);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL).build();

        for (Venue venue : venues()) {
            if (!only.contains(venue.name())) {
                continue;
            }
            long began = System.nanoTime();
            List<String> symbols;
            try {
                symbols = venue.listSymbols().apply(client);
            } catch (RuntimeException e) {
                System.out.printf("!! %s: could not list symbols: %s%n", venue.name(), e.getMessage());
                continue;
            }
            System.out.printf("%n=== %s: %d perpetuals ===%n", venue.name(), symbols.size());
            int rows = 0;
            int failed = 0;
            for (String symbol : symbols) {
                try {
                    rows += importSymbol(client, database, venue, symbol, floor);
                } catch (RuntimeException e) {
                    // Logged with the symbol and cause. A bare counter said "6 failed" and left the
                    // reason to be reconstructed later by diffing the venue's market list against
                    // the table - which is work the importer should have done at the time.
                    System.out.printf("!! %s %s: %s%n", venue.name(), symbol, e.getMessage());
                    failed++;
                }
            }
            System.out.printf("%s DONE: %,d rows, %d symbols, %d failed, %d min%n",
                    venue.name(), rows, symbols.size(), failed,
                    (System.nanoTime() - began) / 60_000_000_000L);
        }
    }

    private static int importSymbol(HttpClient client, DatabaseConfig database, Venue venue,
                                    String symbol, Instant floor) {
        // Binance predates the venue tables and keys on (symbol, funding_time, rate_type) with no
        // base column; the others share one shape. Branching here rather than reshaping the older
        // table keeps every existing reader working.
        boolean binance = "binance".equals(venue.name());
        String upsert = binance
                ? "INSERT INTO binance_perp_funding_rate (symbol, funding_time, rate_type, funding_rate)"
                  + " VALUES (?,?,'REST',?)"
                  + " ON CONFLICT (symbol, funding_time, rate_type) DO UPDATE SET"
                  + " funding_rate = EXCLUDED.funding_rate, updated_at = now()"
                : "INSERT INTO " + venue.table()
                  + " (venue_symbol, base, funding_time, funding_rate) VALUES (?,?,?,?)"
                  + " ON CONFLICT (venue_symbol, funding_time) DO UPDATE SET"
                  + " funding_rate = EXCLUDED.funding_rate, updated_at = now()";
        int written = 0;
        // Bitget pages by page NUMBER; every other venue pages by a millisecond cursor. Both are
        // carried in the same variable because both are "the thing that identifies the next page".
        Long cursor = "bitget".equals(venue.name()) ? 1L : null;
        try (Connection connection = DriverManager.getConnection(
                database.url(), database.user(), database.password())) {
            connection.setAutoCommit(false);
            for (int page = 0; page < 400; page++) {
                List<Payment> payments = venue.parse().apply(
                        get(client, venue.pageUrl().apply(symbol, cursor)));
                if (payments.isEmpty()) {
                    break;
                }
                Instant oldest = payments.get(0).time();
                try (PreparedStatement statement = connection.prepareStatement(upsert)) {
                    for (Payment payment : payments) {
                        if (payment.time().isBefore(oldest)) {
                            oldest = payment.time();
                        }
                        if (binance) {
                            statement.setString(1, symbol);
                            statement.setObject(2, payment.time().atOffset(ZoneOffset.UTC));
                            statement.setBigDecimal(3, payment.rate());
                        } else {
                            statement.setString(1, symbol);
                            statement.setString(2, venue.base().apply(symbol));
                            statement.setObject(3, payment.time().atOffset(ZoneOffset.UTC));
                            statement.setBigDecimal(4, payment.rate());
                        }
                        statement.addBatch();
                    }
                    written += statement.executeBatch().length;
                }
                connection.commit();
                if (oldest.isBefore(floor) || payments.size() < venue.pageSize()) {
                    break;
                }
                cursor = "bitget".equals(venue.name()) ? cursor + 1 : oldest.toEpochMilli();
            }
            return written;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed storing " + venue.name() + " " + symbol, e);
        }
    }

    private static List<Payment> payments(JsonNode array, String timeField, String rateField,
                                          long timeMultiplier) {
        List<Payment> out = new ArrayList<>();
        for (JsonNode row : array) {
            JsonNode time = row.get(timeField);
            JsonNode rate = row.get(rateField);
            if (time == null || rate == null || rate.asText().isBlank()) {
                continue;
            }
            try {
                Instant stamp = Instant.ofEpochMilli(Long.parseLong(time.asText()) * timeMultiplier);
                // Plausibility guard. A single Bybit row arrived with a zero timestamp and landed at
                // 1970-01-01, where it silently skews any min() or range join rather than failing.
                // The same shape of error is what a wrong seconds/milliseconds multiplier produces,
                // so the check is on the resulting instant rather than on the raw field.
                if (stamp.isBefore(PLAUSIBLE_FROM) || stamp.isAfter(PLAUSIBLE_TO)) {
                    continue;
                }
                out.add(new Payment(stamp, new BigDecimal(rate.asText())));
            } catch (NumberFormatException e) {
                // One malformed row must not cost a symbol its history.
            }
        }
        return out;
    }

    private static List<String> jsonList(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        for (JsonNode row : array) {
            JsonNode value = row.get(field);
            if (value != null) {
                out.add(value.asText());
            }
        }
        return out;
    }

    private static String strip(String symbol, String... suffixes) {
        for (String suffix : suffixes) {
            if (symbol.endsWith(suffix) && symbol.length() > suffix.length()) {
                return symbol.substring(0, symbol.length() - suffix.length());
            }
        }
        return symbol;
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
