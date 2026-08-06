package com.smalistean.propstrategy.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

public final class PostgresKlineRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO futures_kline (
                symbol, interval, open_time, open_price, high_price, low_price,
                close_price, volume, close_time, quote_asset_volume, trade_count,
                taker_buy_base_volume, taker_buy_quote_volume
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (symbol, interval, open_time) DO UPDATE SET
                open_price = EXCLUDED.open_price,
                high_price = EXCLUDED.high_price,
                low_price = EXCLUDED.low_price,
                close_price = EXCLUDED.close_price,
                volume = EXCLUDED.volume,
                close_time = EXCLUDED.close_time,
                quote_asset_volume = EXCLUDED.quote_asset_volume,
                trade_count = EXCLUDED.trade_count,
                taker_buy_base_volume = EXCLUDED.taker_buy_base_volume,
                taker_buy_quote_volume = EXCLUDED.taker_buy_quote_volume,
                updated_at = NOW()
            """;

    private final DatabaseConfig config;

    public PostgresKlineRepository(DatabaseConfig config) {
        this.config = config;
    }

    public int upsertAll(String symbol, String interval, List<Kline> klines) {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(UPSERT_SQL)) {
                for (Kline kline : klines) {
                    bind(statement, symbol, interval, kline);
                    statement.addBatch();
                }
                int[] results = statement.executeBatch();
                connection.commit();
                return results.length;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to upsert Futures klines", e);
        }
    }

    public long count(String symbol, String interval) {
        String sql = "SELECT COUNT(*) FROM futures_kline WHERE symbol = ? AND interval = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            statement.setString(2, interval);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count Futures klines", e);
        }
    }

    public Optional<Instant> latestOpenTime(String symbol, String interval) {
        String sql = "SELECT MAX(open_time) FROM futures_kline WHERE symbol = ? AND interval = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            statement.setString(2, interval);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                OffsetDateTime latest = resultSet.getObject(1, OffsetDateTime.class);
                return latest == null ? Optional.empty() : Optional.of(latest.toInstant());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read latest Futures kline", e);
        }
    }

    public KlineRangeStats rangeStats(String symbol, String interval,
                                      Instant startInclusive, Instant endExclusive) {
        String sql = """
                SELECT COUNT(*), MIN(open_time), MAX(open_time)
                FROM futures_kline
                WHERE symbol = ? AND interval = ? AND open_time >= ? AND open_time < ?
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            statement.setString(2, interval);
            statement.setObject(3, OffsetDateTime.ofInstant(startInclusive, ZoneOffset.UTC));
            statement.setObject(4, OffsetDateTime.ofInstant(endExclusive, ZoneOffset.UTC));
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                long count = resultSet.getLong(1);
                OffsetDateTime first = resultSet.getObject(2, OffsetDateTime.class);
                OffsetDateTime last = resultSet.getObject(3, OffsetDateTime.class);
                return new KlineRangeStats(
                        count,
                        first == null ? null : first.toInstant(),
                        last == null ? null : last.toInstant());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read Futures kline range stats", e);
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(config.url(), config.user(), config.password());
    }

    private static void bind(PreparedStatement statement, String symbol, String interval,
                             Kline kline) throws SQLException {
        statement.setString(1, symbol);
        statement.setString(2, interval);
        statement.setObject(3, OffsetDateTime.ofInstant(kline.openTime(), ZoneOffset.UTC));
        statement.setBigDecimal(4, kline.open());
        statement.setBigDecimal(5, kline.high());
        statement.setBigDecimal(6, kline.low());
        statement.setBigDecimal(7, kline.close());
        statement.setBigDecimal(8, kline.volume());
        statement.setObject(9, OffsetDateTime.ofInstant(kline.closeTime(), ZoneOffset.UTC));
        statement.setBigDecimal(10, kline.quoteAssetVolume());
        statement.setInt(11, kline.tradeCount());
        statement.setBigDecimal(12, kline.takerBuyBaseVolume());
        statement.setBigDecimal(13, kline.takerBuyQuoteVolume());
    }

    public record KlineRangeStats(long count, Instant firstOpenTime, Instant lastOpenTime) {
    }
}
