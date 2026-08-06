package com.smalistean.propstrategy.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

public final class PostgresFundingRateRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO futures_funding_rate (
                symbol, funding_time, rate_type, funding_rate, mark_price
            ) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (symbol, funding_time, rate_type) DO UPDATE SET
                funding_rate = EXCLUDED.funding_rate,
                mark_price = EXCLUDED.mark_price,
                updated_at = NOW()
            """;

    private final DatabaseConfig config;

    public PostgresFundingRateRepository(DatabaseConfig config) {
        this.config = config;
    }

    public int upsertAll(List<FundingRate> rates) {
        if (rates.isEmpty()) {
            return 0;
        }
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(UPSERT_SQL)) {
                for (FundingRate rate : rates) {
                    statement.setString(1, rate.symbol());
                    statement.setObject(2, OffsetDateTime.ofInstant(rate.fundingTime(), ZoneOffset.UTC));
                    statement.setString(3, rate.rateType());
                    statement.setBigDecimal(4, rate.fundingRate());
                    statement.setBigDecimal(5, rate.markPrice());
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
            throw new IllegalStateException("Failed to upsert Futures funding rates", e);
        }
    }

    public Optional<Instant> latestFundingTime(String symbol) {
        String sql = "SELECT MAX(funding_time) FROM futures_funding_rate WHERE symbol = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                OffsetDateTime latest = resultSet.getObject(1, OffsetDateTime.class);
                return latest == null ? Optional.empty() : Optional.of(latest.toInstant());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read latest Futures funding rate", e);
        }
    }

    public long count(String symbol) {
        String sql = "SELECT COUNT(*) FROM futures_funding_rate WHERE symbol = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count Futures funding rates", e);
        }
    }

    public List<FundingRate> findThrough(String symbol, Instant endInclusive) {
        String sql = """
                SELECT symbol, funding_time, rate_type, funding_rate, mark_price
                FROM futures_funding_rate
                WHERE symbol = ? AND funding_time <= ?
                ORDER BY funding_time, rate_type
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            statement.setObject(2, OffsetDateTime.ofInstant(endInclusive, ZoneOffset.UTC));
            try (ResultSet resultSet = statement.executeQuery()) {
                var result = new java.util.ArrayList<FundingRate>();
                while (resultSet.next()) {
                    result.add(new FundingRate(
                            resultSet.getString("symbol"),
                            resultSet.getObject("funding_time", OffsetDateTime.class).toInstant(),
                            resultSet.getString("rate_type"),
                            resultSet.getBigDecimal("funding_rate"),
                            resultSet.getBigDecimal("mark_price")));
                }
                return List.copyOf(result);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load Futures funding rates", e);
        }
    }

    public List<FundingRate> findRange(String symbol, Instant startInclusive,
                                       Instant endExclusive) {
        String sql = """
                SELECT symbol, funding_time, rate_type, funding_rate, mark_price
                FROM futures_funding_rate
                WHERE symbol = ? AND funding_time >= ? AND funding_time < ?
                ORDER BY funding_time, rate_type
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            statement.setObject(2, OffsetDateTime.ofInstant(startInclusive, ZoneOffset.UTC));
            statement.setObject(3, OffsetDateTime.ofInstant(endExclusive, ZoneOffset.UTC));
            try (ResultSet resultSet = statement.executeQuery()) {
                var result = new java.util.ArrayList<FundingRate>();
                while (resultSet.next()) {
                    result.add(new FundingRate(
                            resultSet.getString("symbol"),
                            resultSet.getObject("funding_time", OffsetDateTime.class).toInstant(),
                            resultSet.getString("rate_type"),
                            resultSet.getBigDecimal("funding_rate"),
                            resultSet.getBigDecimal("mark_price")));
                }
                return List.copyOf(result);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load Futures funding-rate range", e);
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(config.url(), config.user(), config.password());
    }
}
