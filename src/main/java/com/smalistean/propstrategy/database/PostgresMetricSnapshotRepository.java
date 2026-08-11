package com.smalistean.propstrategy.database;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Binance futures "metrics" archives: open interest and positioning at 5-minute resolution. */
public final class PostgresMetricSnapshotRepository {

    /** One row per symbol-instant. Any column may be absent in the source and is stored as NULL. */
    public record Snapshot(String symbol, Instant time, BigDecimal openInterest,
                           BigDecimal openInterestValue, BigDecimal topTraderAccountRatio,
                           BigDecimal topTraderPositionRatio, BigDecimal accountRatio,
                           BigDecimal takerVolumeRatio) {
    }

    private static final String UPSERT = """
            INSERT INTO futures_metric_snapshot
              (symbol, snapshot_time, sum_open_interest, sum_open_interest_value,
               count_toptrader_long_short_ratio, sum_toptrader_long_short_ratio,
               count_long_short_ratio, sum_taker_long_short_vol_ratio)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (symbol, snapshot_time) DO UPDATE SET
              sum_open_interest=EXCLUDED.sum_open_interest,
              sum_open_interest_value=EXCLUDED.sum_open_interest_value,
              count_toptrader_long_short_ratio=EXCLUDED.count_toptrader_long_short_ratio,
              sum_toptrader_long_short_ratio=EXCLUDED.sum_toptrader_long_short_ratio,
              count_long_short_ratio=EXCLUDED.count_long_short_ratio,
              sum_taker_long_short_vol_ratio=EXCLUDED.sum_taker_long_short_vol_ratio,
              updated_at=NOW()
            """;

    private final DatabaseConfig config;

    public PostgresMetricSnapshotRepository(DatabaseConfig config) {
        this.config = config;
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(config.url(), config.user(), config.password());
    }

    /**
     * Writes a day's snapshots and records the day as imported in one transaction, so a day is
     * never marked complete unless its rows committed with it. That is what makes a re-run after an
     * interruption safe to resume rather than needing a manual audit.
     */
    public int importDay(String symbol, LocalDate day, List<Snapshot> rows, String status) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                if (!rows.isEmpty()) {
                    try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
                        for (Snapshot row : rows) {
                            statement.setString(1, row.symbol());
                            statement.setObject(2, OffsetDateTime.ofInstant(row.time(), ZoneOffset.UTC));
                            setDecimal(statement, 3, row.openInterest());
                            setDecimal(statement, 4, row.openInterestValue());
                            setDecimal(statement, 5, row.topTraderAccountRatio());
                            setDecimal(statement, 6, row.topTraderPositionRatio());
                            setDecimal(statement, 7, row.accountRatio());
                            setDecimal(statement, 8, row.takerVolumeRatio());
                            statement.addBatch();
                        }
                        statement.executeBatch();
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO futures_metric_import (symbol, archive_day, row_count, status)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (symbol, archive_day) DO UPDATE SET
                          row_count=EXCLUDED.row_count, status=EXCLUDED.status, imported_at=NOW()
                        """)) {
                    statement.setString(1, symbol);
                    statement.setObject(2, day);
                    statement.setInt(3, rows.size());
                    statement.setString(4, status);
                    statement.executeUpdate();
                }
                connection.commit();
                return rows.size();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to import metrics for " + symbol + " " + day, e);
        }
    }

    /** Days already recorded for this symbol, so a resumed run skips them without re-downloading. */
    public Set<LocalDate> importedDays(String symbol) {
        Set<LocalDate> days = new HashSet<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT archive_day FROM futures_metric_import WHERE symbol = ?")) {
            statement.setString(1, symbol);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    days.add(results.getObject(1, LocalDate.class));
                }
            }
            return days;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read metric import progress for " + symbol, e);
        }
    }

    /** Snapshots for one symbol over a range, ascending by time. */
    public java.util.List<Snapshot> findRange(String symbol, Instant start, Instant end) {
        String sql = """
                SELECT snapshot_time, sum_open_interest, sum_open_interest_value,
                       count_toptrader_long_short_ratio, sum_toptrader_long_short_ratio,
                       count_long_short_ratio, sum_taker_long_short_vol_ratio
                FROM futures_metric_snapshot
                WHERE symbol=? AND snapshot_time>=? AND snapshot_time<?
                ORDER BY snapshot_time
                """;
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            statement.setObject(2, OffsetDateTime.ofInstant(start, ZoneOffset.UTC));
            statement.setObject(3, OffsetDateTime.ofInstant(end, ZoneOffset.UTC));
            java.util.List<Snapshot> rows = new java.util.ArrayList<>();
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    rows.add(new Snapshot(symbol,
                            results.getObject(1, OffsetDateTime.class).toInstant(),
                            results.getBigDecimal(2), results.getBigDecimal(3),
                            results.getBigDecimal(4), results.getBigDecimal(5),
                            results.getBigDecimal(6), results.getBigDecimal(7)));
                }
            }
            return java.util.List.copyOf(rows);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read metric snapshots for " + symbol, e);
        }
    }

    private static void setDecimal(PreparedStatement statement, int index, BigDecimal value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.NUMERIC);
        } else {
            statement.setBigDecimal(index, value);
        }
    }
}
