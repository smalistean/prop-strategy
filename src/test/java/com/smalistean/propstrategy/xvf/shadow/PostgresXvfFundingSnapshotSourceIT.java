package com.smalistean.propstrategy.xvf.shadow;

import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.DatabaseMigrator;
import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot.Freshness;
import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot.IntervalSource;
import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot.PendingObservation;
import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot.PendingVenueWatermark;
import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot.SettledVenueWatermark;
import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshotSource.FreshnessPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresXvfFundingSnapshotSourceIT {

    private static PostgreSQLContainer postgres;
    private static DatabaseConfig database;
    private static PostgresXvfFundingSnapshotSource source;

    @BeforeAll
    static void startPostgres() {
        postgres = new PostgreSQLContainer("postgres:17.10-alpine")
                .withDatabaseName("xvf_funding_snapshot")
                .withUsername("xvf_test")
                .withPassword("xvf_test");
        postgres.start();
        database = new DatabaseConfig(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        DatabaseMigrator.migrate(database);
        source = new PostgresXvfFundingSnapshotSource(database);
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void clearFundingData() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE venue_funding_observation,
                             binance_perp_funding_rate,
                             bybit_perp_funding_rate,
                             hyperliquid_perp_funding_rate,
                             dydx_perp_funding_rate
                    """);
        }
    }

    @Test
    void readsLatestPendingObservationAtCutoffWithExactValuesAndExplicitFreshness() throws Exception {
        Instant cutoff = Instant.parse("2026-08-21T10:00:00Z");

        observation("binance", "BTCUSDT", "2026-08-21T00:00:00Z",
                "2026-08-21T00:00:00.123456Z", "2026-08-21T08:00:00Z", "0.000100000000");
        observation("binance", "BTCUSDT", "2026-08-21T06:00:00Z",
                "2026-08-21T06:40:00Z", "2026-08-21T08:00:00Z", "0.000200000000");
        observation("binance", "BTCUSDT", "2026-08-21T07:00:00Z",
                "2026-08-21T07:40:00Z", "2026-08-21T08:00:00Z", "0.000300000000");
        observation("binance", "BTCUSDT", "2026-08-21T08:00:00Z",
                "2026-08-21T08:40:00Z", "2026-08-21T16:00:00Z", "0.000400000000");
        observation("binance", "BTCUSDT", "2026-08-21T09:00:00Z",
                "2026-08-21T09:40:00.654321Z", "2026-08-21T16:00:00Z", "0.001234567890");
        // Must not leak through the decision cutoff even though it shares the cutoff's observed hour.
        observation("binance", "BTCUSDT", "2026-08-21T10:00:00Z",
                "2026-08-21T10:00:00.000001Z", "2026-08-21T16:00:00Z", "0.900000000000");
        observation("binance", "ETHUSDT", "2026-08-21T07:00:00Z",
                "2026-08-21T07:10:00Z", "2026-08-21T08:00:00Z", "-0.000200000000");

        observation("bybit", "SOLUSDT", "2026-08-21T00:00:00Z",
                "2026-08-21T00:10:00Z", "2026-08-21T08:00:00Z", "0.000300000000");
        observation("bybit", "SOLUSDT", "2026-08-21T09:00:00Z",
                "2026-08-21T09:50:00Z", "2026-08-21T16:00:00Z", "0.000400000000");
        observation("bybit", "DOGEUSDT", "2026-08-21T09:00:00Z",
                "2026-08-21T09:55:00Z", null, "0.000500000000");

        observation("hyperliquid", "HYPE", "2026-08-21T06:00:00Z",
                "2026-08-21T06:02:00Z", "2026-08-21T07:00:00Z", "0.000600000000");
        observation("hyperliquid", "HYPE", "2026-08-21T07:00:00Z",
                "2026-08-21T07:02:00Z", "2026-08-21T08:00:00Z", "0.000700000000");
        observation("dydx", "BTC-USD", "2026-08-21T09:00:00Z",
                "2026-08-21T09:59:00Z", "2026-08-21T11:00:00Z", "0.800000000000");

        XvfFundingSnapshot snapshot = source.read(cutoff,
                new FreshnessPolicy(Duration.ofMinutes(30), Duration.ofHours(2)));

        assertEquals(cutoff, snapshot.cutoffUtc());
        assertEquals(5, snapshot.pendingByInstrument().size());
        assertFalse(snapshot.pending("dydx", "BTC-USD").isPresent());

        PendingObservation btc = snapshot.pending("binance", "BTCUSDT").orElseThrow();
        assertDecimalEquals("0.001234567890", btc.fundingRate());
        assertEquals(Instant.parse("2026-08-21T09:00:00Z"), btc.observedHour());
        assertEquals(Instant.parse("2026-08-21T09:40:00.654321Z"), btc.observedAt());
        assertEquals(Instant.parse("2026-08-21T16:00:00Z"), btc.targetStamp());
        assertEquals(8, btc.fundingIntervalHours());
        assertEquals(IntervalSource.TARGET_STAMP_DELTA, btc.intervalSource());
        assertEquals(Freshness.FRESH, btc.freshness());

        var btcHistory = snapshot.pendingHistory("binance", "BTCUSDT");
        assertEquals(4, btcHistory.size());
        assertEquals(Instant.parse("2026-08-21T06:00:00Z"),
                btcHistory.getFirst().observedHour());
        assertEquals(Instant.parse("2026-08-21T09:00:00Z"),
                btcHistory.getLast().observedHour());
        assertEquals(btc, btcHistory.getLast());
        assertTrue(btcHistory.stream().noneMatch(observation ->
                observation.observedAt().isAfter(cutoff)));

        PendingObservation stale = snapshot.pending("binance", "ETHUSDT").orElseThrow();
        assertEquals(Freshness.STALE, stale.freshness());
        assertNull(stale.fundingIntervalHours());
        assertEquals(IntervalSource.UNKNOWN, stale.intervalSource());

        PendingObservation noStamp = snapshot.pending("bybit", "DOGEUSDT").orElseThrow();
        assertNull(noStamp.targetStamp());
        assertNull(noStamp.fundingIntervalHours());
        assertEquals(IntervalSource.UNKNOWN, noStamp.intervalSource());

        PendingObservation hourly = snapshot.pending("hyperliquid", "HYPE").orElseThrow();
        assertEquals(1, hourly.fundingIntervalHours());
        assertEquals(Freshness.STALE, hourly.freshness());

        PendingVenueWatermark binance = pendingWatermark(snapshot, "binance");
        assertEquals(2, binance.symbolCount());
        assertEquals(1, binance.freshSymbolCount());
        assertEquals(1, binance.staleSymbolCount());
        assertEquals(Freshness.FRESH, binance.freshness());
        assertEquals(Instant.parse("2026-08-21T09:40:00.654321Z"), binance.latestObservedAt());
    }

    @Test
    void readsSettledWatermarksSeparatelyForEveryActiveVenueAndNeverPastCutoff() throws Exception {
        Instant cutoff = Instant.parse("2026-08-21T10:00:00Z");
        binanceSettled("BTCUSDT", "2026-08-21T07:00:00Z", "0.0001");
        binanceSettled("BTCUSDT", "2026-08-21T11:00:00Z", "0.0002");
        bybitSettled("ETHUSDT", "ETH", "2026-08-21T09:30:00Z", "0.0003");
        dydxSettled("BTC-USD", "BTC", "2026-08-21T09:50:00Z", "0.0004");

        XvfFundingSnapshot snapshot = source.read(cutoff,
                new FreshnessPolicy(Duration.ofMinutes(90), Duration.ofHours(2)));

        assertEquals(3, snapshot.pendingWatermarks().size());
        for (PendingVenueWatermark watermark : snapshot.pendingWatermarks()) {
            assertEquals(Freshness.MISSING, watermark.freshness());
            assertNull(watermark.latestObservedAt());
            assertEquals(0, watermark.symbolCount());
        }

        assertEquals(3, snapshot.settledWatermarks().size());
        SettledVenueWatermark binance = settledWatermark(snapshot, "binance");
        assertEquals(Instant.parse("2026-08-21T07:00:00Z"), binance.latestFundingTime());
        assertEquals(Freshness.STALE, binance.freshness());

        SettledVenueWatermark bybit = settledWatermark(snapshot, "bybit");
        assertEquals(Instant.parse("2026-08-21T09:30:00Z"), bybit.latestFundingTime());
        assertEquals(Freshness.FRESH, bybit.freshness());

        SettledVenueWatermark hyperliquid = settledWatermark(snapshot, "hyperliquid");
        assertNull(hyperliquid.latestFundingTime());
        assertEquals(Freshness.MISSING, hyperliquid.freshness());
        assertTrue(snapshot.settledWatermarks().stream()
                .noneMatch(watermark -> "dydx".equals(watermark.venue())));
    }

    private static PendingVenueWatermark pendingWatermark(XvfFundingSnapshot snapshot, String venue) {
        return snapshot.pendingWatermarks().stream()
                .filter(watermark -> venue.equals(watermark.venue())).findFirst().orElseThrow();
    }

    private static SettledVenueWatermark settledWatermark(XvfFundingSnapshot snapshot, String venue) {
        return snapshot.settledWatermarks().stream()
                .filter(watermark -> venue.equals(watermark.venue())).findFirst().orElseThrow();
    }

    private static void observation(String venue, String symbol, String observedHour,
                                    String observedAt, String targetStamp, String rate) throws SQLException {
        String sql = """
                INSERT INTO venue_funding_observation
                    (venue, venue_symbol, observed_hour, observed_at, target_stamp, funding_rate)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, venue);
            statement.setString(2, symbol);
            statement.setObject(3, utc(observedHour));
            statement.setObject(4, utc(observedAt));
            if (targetStamp == null) {
                statement.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                statement.setObject(5, utc(targetStamp));
            }
            statement.setBigDecimal(6, new BigDecimal(rate));
            statement.executeUpdate();
        }
    }

    private static void binanceSettled(String symbol, String time, String rate) throws SQLException {
        String sql = """
                INSERT INTO binance_perp_funding_rate
                    (symbol, funding_time, rate_type, funding_rate, mark_price)
                VALUES (?, ?, 'FUNDING_RATE', ?, 100)
                """;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            statement.setObject(2, utc(time));
            statement.setBigDecimal(3, new BigDecimal(rate));
            statement.executeUpdate();
        }
    }

    private static void bybitSettled(String symbol, String base, String time, String rate)
            throws SQLException {
        String sql = """
                INSERT INTO bybit_perp_funding_rate
                    (venue_symbol, base, funding_time, funding_rate)
                VALUES (?, ?, ?, ?)
                """;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            statement.setString(2, base);
            statement.setObject(3, utc(time));
            statement.setBigDecimal(4, new BigDecimal(rate));
            statement.executeUpdate();
        }
    }

    private static void dydxSettled(String symbol, String base, String time, String rate)
            throws SQLException {
        String sql = """
                INSERT INTO dydx_perp_funding_rate
                    (venue_symbol, base, funding_time, funding_rate)
                VALUES (?, ?, ?, ?)
                """;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            statement.setString(2, base);
            statement.setObject(3, utc(time));
            statement.setBigDecimal(4, new BigDecimal(rate));
            statement.executeUpdate();
        }
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(database.url(), database.user(), database.password());
    }

    private static OffsetDateTime utc(String instant) {
        return OffsetDateTime.ofInstant(Instant.parse(instant), ZoneOffset.UTC);
    }

    private static void assertDecimalEquals(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> expected + " != " + actual);
    }
}
