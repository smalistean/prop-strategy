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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Records one Deribit option-chain snapshot into {@code deribit_option_quote}, then exits.
 *
 * <h2>Why this exists and why it is one-shot</h2>
 * Item 4 in {@code RESEARCH_OPTIONS.md} compares the options-implied forward rate against perpetual
 * funding. Put-call parity needs quotes, and Deribit publishes only trade history for free -
 * {@code get_last_trades_by_instrument} returns nothing for the strikes parity depends on, because
 * most strikes do not trade on a given day. So the data has to be accumulated going forward.
 *
 * <p>It takes a single snapshot and exits rather than running as a daemon, because the accumulation
 * needs to survive reboots and months of wall-clock time. A scheduler restarting a short process is
 * durable in a way a long-lived process is not; a daemon that dies quietly leaves a gap that looks
 * identical to a period when nothing traded.
 *
 * <h2>Settlement conventions</h2>
 * BTC and ETH options are inverse: {@code mark_price} is denominated in the base coin, so a USD price
 * is {@code mark_price * underlying_price}. The USDC chains are linear and already in USDC.
 * {@code quote_currency} records which, and parity that ignores it is wrong by a factor of spot.
 *
 * <h2>Usage</h2>
 * <pre>
 *   -DderibitCurrencies=BTC,ETH,USDC   currency buckets to snapshot (USDC covers 7 underlyings)
 * </pre>
 * Public market data only - no API key, and it places no orders.
 */
public final class DeribitChainSnapshotApplication {

    private static final String API = "https://www.deribit.com/api/v2/public/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String UPSERT = """
            INSERT INTO deribit_option_quote (
                snapshot_time, instrument_name, underlying, quote_currency, expiry_time, strike,
                option_type, bid_price, ask_price, mark_price, mark_iv, underlying_price,
                index_price, open_interest, volume_24h)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (snapshot_time, instrument_name) DO NOTHING
            """;

    private DeribitChainSnapshotApplication() {
    }

    public static void main(String[] args) throws Exception {
        List<String> currencies = List.of(System.getProperty("deribitCurrencies", "BTC,ETH,USDC")
                .trim().toUpperCase().split(","));

        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        DatabaseMigrator.migrate(database);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL).build();

        // Truncated to the hour so a scheduler firing a little early or late still lands on one slot
        // per hour, and a retry within the same hour is idempotent against the primary key.
        Instant snapshot = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS);

        int total = 0;
        try (Connection connection = DriverManager.getConnection(
                database.url(), database.user(), database.password())) {
            connection.setAutoCommit(false);
            for (String currency : currencies) {
                // Expiry comes from get_instruments rather than by parsing "16AUG26" out of the
                // instrument name. The name carries no time of day, and inferring one would silently
                // shift every annualised rate computed from it.
                Map<String, Long> expiries = new HashMap<>();
                for (JsonNode instrument : call(client, "get_instruments?currency=" + currency
                        + "&kind=option&expired=false")) {
                    expiries.put(instrument.get("instrument_name").asText(),
                            instrument.get("expiration_timestamp").asLong());
                }
                int written = store(connection, snapshot,
                        call(client, "get_book_summary_by_currency?currency=" + currency + "&kind=option"),
                        expiries);
                connection.commit();
                total += written;
                System.out.printf("%-6s %,5d instruments stored%n", currency, written);
            }
        }
        System.out.printf("DERIBIT SNAPSHOT %s: %,d rows%n", snapshot, total);
    }

    private static int store(Connection connection, Instant snapshot, JsonNode book,
                             Map<String, Long> expiries) throws SQLException {
        int written = 0;
        try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
            for (JsonNode row : book) {
                String name = row.get("instrument_name").asText();
                Long expiry = expiries.get(name);
                String[] parts = name.split("-");
                if (expiry == null || parts.length != 4) {
                    // An instrument present in the book but absent from the instrument list is
                    // skipped rather than stored with a guessed expiry.
                    continue;
                }
                statement.setObject(1, snapshot.atOffset(ZoneOffset.UTC));
                statement.setString(2, name);
                statement.setString(3, parts[0]);
                statement.setString(4, row.path("quote_currency").asText("?"));
                statement.setObject(5, Instant.ofEpochMilli(expiry).atOffset(ZoneOffset.UTC));
                // Deribit writes a decimal point as 'd' in instrument names, so XRP_USDC-25DEC26-1d05-P
                // has strike 1.05. This affects 720 of 2,654 USDC instruments - every sub-$10
                // underlying - and none of the BTC or ETH chains, which is why it survives a test
                // against majors alone.
                statement.setBigDecimal(6, new BigDecimal(parts[2].replace('d', '.')));
                statement.setString(7, parts[3]);
                setDecimal(statement, 8, row.get("bid_price"));
                setDecimal(statement, 9, row.get("ask_price"));
                JsonNode mark = row.get("mark_price");
                if (mark == null || mark.isNull()) {
                    continue; // mark is the only non-null price column; without it the row is useless
                }
                statement.setBigDecimal(10, mark.decimalValue());
                setDecimal(statement, 11, row.get("mark_iv"));
                setDecimal(statement, 12, row.get("underlying_price"));
                setDecimal(statement, 13, row.get("estimated_delivery_price"));
                setDecimal(statement, 14, row.get("open_interest"));
                setDecimal(statement, 15, row.get("volume"));
                statement.addBatch();
                written++;
            }
            statement.executeBatch();
        }
        return written;
    }

    /** An absent bid or ask is stored as NULL: an empty side is a real market state, not a zero. */
    private static void setDecimal(PreparedStatement statement, int index, JsonNode value)
            throws SQLException {
        if (value == null || value.isNull()) {
            statement.setNull(index, Types.NUMERIC);
        } else {
            statement.setBigDecimal(index, value.decimalValue());
        }
    }

    private static JsonNode call(HttpClient client, String path) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(API + path))
                                .timeout(Duration.ofSeconds(60)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("HTTP " + response.statusCode() + " for " + path);
                }
                return MAPPER.readTree(response.body()).get("result");
            } catch (java.io.IOException | IllegalStateException e) {
                last = e instanceof IllegalStateException illegal
                        ? illegal : new IllegalStateException("Request failed: " + path, e);
                try {
                    Thread.sleep(1_000L * attempt * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted", interrupted);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted", e);
            }
        }
        throw last;
    }
}
