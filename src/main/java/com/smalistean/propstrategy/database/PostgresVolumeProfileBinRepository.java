package com.smalistean.propstrategy.database;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

public final class PostgresVolumeProfileBinRepository {
    private static final String UPSERT = """
            INSERT INTO binance_perp_volume_profile_bin
              (symbol, bucket_time, bucket_minutes, price_step, price_from,
               aggregate_trade_count, base_volume, quote_notional,
               aggressive_buy_quote, aggressive_sell_quote)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (symbol, bucket_time, bucket_minutes, price_step, price_from)
            DO UPDATE SET aggregate_trade_count=EXCLUDED.aggregate_trade_count,
              base_volume=EXCLUDED.base_volume, quote_notional=EXCLUDED.quote_notional,
              aggressive_buy_quote=EXCLUDED.aggressive_buy_quote,
              aggressive_sell_quote=EXCLUDED.aggressive_sell_quote, updated_at=NOW()
            """;
    private final DatabaseConfig config;

    public PostgresVolumeProfileBinRepository(DatabaseConfig config) {
        this.config = config;
    }

    public int upsertAll(List<VolumeProfileBin> bins) {
        if (bins.isEmpty()) return 0;
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
                for (VolumeProfileBin bin : bins) {
                    int i = 1;
                    statement.setString(i++, bin.symbol());
                    statement.setObject(i++, utc(bin.bucketTime()));
                    statement.setInt(i++, bin.bucketMinutes());
                    statement.setBigDecimal(i++, bin.priceStep());
                    statement.setBigDecimal(i++, bin.priceFrom());
                    statement.setLong(i++, bin.aggregateTradeCount());
                    statement.setBigDecimal(i++, bin.baseVolume());
                    statement.setBigDecimal(i++, bin.quoteNotional());
                    statement.setBigDecimal(i++, bin.aggressiveBuyQuote());
                    statement.setBigDecimal(i, bin.aggressiveSellQuote());
                    statement.addBatch();
                }
                int result = statement.executeBatch().length;
                connection.commit();
                return result;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to upsert volume-profile bins", e);
        }
    }

    public List<VolumeProfileBin> findRange(String symbol, int bucketMinutes,
                                            BigDecimal priceStep, Instant start, Instant end) {
        String sql = """
                SELECT bucket_time, price_from, aggregate_trade_count, base_volume,
                       quote_notional, aggressive_buy_quote, aggressive_sell_quote
                FROM binance_perp_volume_profile_bin
                WHERE symbol=? AND bucket_minutes=? AND price_step=?
                  AND bucket_time>=? AND bucket_time<?
                ORDER BY bucket_time, price_from
                """;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol); statement.setInt(2, bucketMinutes);
            statement.setBigDecimal(3, priceStep); statement.setObject(4, utc(start));
            statement.setObject(5, utc(end));
            try (ResultSet rows = statement.executeQuery()) {
                List<VolumeProfileBin> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new VolumeProfileBin(symbol,
                            rows.getObject("bucket_time", OffsetDateTime.class).toInstant(),
                            bucketMinutes, priceStep, rows.getBigDecimal("price_from"),
                            rows.getLong("aggregate_trade_count"), rows.getBigDecimal("base_volume"),
                            rows.getBigDecimal("quote_notional"), rows.getBigDecimal("aggressive_buy_quote"),
                            rows.getBigDecimal("aggressive_sell_quote")));
                }
                return List.copyOf(result);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load volume-profile bins", e);
        }
    }

    public boolean archiveCompleted(String symbol, String archiveName, int bucketMinutes,
                                    BigDecimal priceStep, String sha256) {
        String sql = "SELECT 1 FROM binance_perp_volume_profile_import WHERE symbol=? AND archive_name=? "
                + "AND bucket_minutes=? AND price_step=? AND archive_sha256=?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol); statement.setString(2, archiveName);
            statement.setInt(3, bucketMinutes); statement.setBigDecimal(4, priceStep);
            statement.setString(5, sha256);
            try (ResultSet rows = statement.executeQuery()) { return rows.next(); }
        } catch (SQLException e) { throw new IllegalStateException("Failed to read profile import", e); }
    }

    public void recordCompleted(String symbol, String archiveName, int bucketMinutes,
                                BigDecimal priceStep, String sha256, long sourceRows, long binRows) {
        String sql = """
                INSERT INTO binance_perp_volume_profile_import
                  (symbol, archive_name, bucket_minutes, price_step, archive_sha256, source_rows, bin_rows)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (symbol, archive_name, bucket_minutes, price_step)
                DO UPDATE SET archive_sha256=EXCLUDED.archive_sha256, source_rows=EXCLUDED.source_rows,
                  bin_rows=EXCLUDED.bin_rows, completed_at=NOW()
                """;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol); statement.setString(2, archiveName);
            statement.setInt(3, bucketMinutes); statement.setBigDecimal(4, priceStep);
            statement.setString(5, sha256); statement.setLong(6, sourceRows); statement.setLong(7, binRows);
            statement.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("Failed to record profile import", e); }
    }

    public long count(String symbol, int bucketMinutes, BigDecimal priceStep, Instant start, Instant end) {
        String sql = "SELECT COUNT(*) FROM binance_perp_volume_profile_bin WHERE symbol=? "
                + "AND bucket_minutes=? AND price_step=? AND bucket_time>=? AND bucket_time<?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol); statement.setInt(2, bucketMinutes);
            statement.setBigDecimal(3, priceStep); statement.setObject(4, utc(start));
            statement.setObject(5, utc(end));
            try (ResultSet rows = statement.executeQuery()) { rows.next(); return rows.getLong(1); }
        } catch (SQLException e) { throw new IllegalStateException("Failed to count profile bins", e); }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(config.url(), config.user(), config.password());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
