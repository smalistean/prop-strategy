package com.smalistean.propstrategy.database;

import com.smalistean.propstrategy.database.TraderRatio.RatioType;

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

public final class PostgresSupportingMarketDataRepository {

    private static final String UPSERT_OPEN_INTEREST = """
            INSERT INTO futures_open_interest_statistic (
                symbol, period, statistic_time, sum_open_interest,
                sum_open_interest_value, circulating_supply
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (symbol, period, statistic_time) DO UPDATE SET
                sum_open_interest = EXCLUDED.sum_open_interest,
                sum_open_interest_value = EXCLUDED.sum_open_interest_value,
                circulating_supply = EXCLUDED.circulating_supply,
                updated_at = NOW()
            """;

    private static final String UPSERT_RATIO = """
            INSERT INTO futures_trader_ratio (
                symbol, period, ratio_type, statistic_time,
                long_short_ratio, long_share, short_share
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (symbol, period, ratio_type, statistic_time) DO UPDATE SET
                long_short_ratio = EXCLUDED.long_short_ratio,
                long_share = EXCLUDED.long_share,
                short_share = EXCLUDED.short_share,
                updated_at = NOW()
            """;

    private final DatabaseConfig config;

    public PostgresSupportingMarketDataRepository(DatabaseConfig config) {
        this.config = config;
    }

    public int upsertOpenInterest(List<OpenInterestStatistic> statistics) {
        if (statistics.isEmpty()) {
            return 0;
        }
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(UPSERT_OPEN_INTEREST)) {
                for (OpenInterestStatistic statistic : statistics) {
                    statement.setString(1, statistic.symbol());
                    statement.setString(2, statistic.period());
                    statement.setObject(3, utc(statistic.statisticTime()));
                    statement.setBigDecimal(4, statistic.sumOpenInterest());
                    statement.setBigDecimal(5, statistic.sumOpenInterestValue());
                    statement.setBigDecimal(6, statistic.circulatingSupply());
                    statement.addBatch();
                }
                return commitBatch(connection, statement);
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to upsert open-interest statistics", e);
        }
    }

    public int upsertRatios(List<TraderRatio> ratios) {
        if (ratios.isEmpty()) {
            return 0;
        }
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(UPSERT_RATIO)) {
                for (TraderRatio ratio : ratios) {
                    statement.setString(1, ratio.symbol());
                    statement.setString(2, ratio.period());
                    statement.setString(3, ratio.ratioType().name());
                    statement.setObject(4, utc(ratio.statisticTime()));
                    statement.setBigDecimal(5, ratio.longShortRatio());
                    statement.setBigDecimal(6, ratio.longShare());
                    statement.setBigDecimal(7, ratio.shortShare());
                    statement.addBatch();
                }
                return commitBatch(connection, statement);
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to upsert trader ratios", e);
        }
    }

    public Optional<Instant> latestOpenInterestTime(String symbol, String period) {
        return latest("futures_open_interest_statistic", symbol, period, null);
    }

    public Optional<Instant> latestRatioTime(String symbol, String period, RatioType type) {
        return latest("futures_trader_ratio", symbol, period, type);
    }

    public long openInterestCount(String symbol, String period) {
        return count("futures_open_interest_statistic", symbol, period, null);
    }

    public long ratioCount(String symbol, String period, RatioType type) {
        return count("futures_trader_ratio", symbol, period, type);
    }

    public List<OpenInterestStatistic> findOpenInterestThrough(
            String symbol, String period, Instant endInclusive) {
        String sql = """
                SELECT symbol, period, statistic_time, sum_open_interest,
                       sum_open_interest_value, circulating_supply
                FROM futures_open_interest_statistic
                WHERE symbol = ? AND period = ? AND statistic_time <= ?
                ORDER BY statistic_time
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            statement.setString(2, period);
            statement.setObject(3, utc(endInclusive));
            try (ResultSet resultSet = statement.executeQuery()) {
                var result = new java.util.ArrayList<OpenInterestStatistic>();
                while (resultSet.next()) {
                    result.add(new OpenInterestStatistic(
                            resultSet.getString("symbol"),
                            resultSet.getString("period"),
                            resultSet.getObject("statistic_time", OffsetDateTime.class).toInstant(),
                            resultSet.getBigDecimal("sum_open_interest"),
                            resultSet.getBigDecimal("sum_open_interest_value"),
                            resultSet.getBigDecimal("circulating_supply")));
                }
                return List.copyOf(result);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load open-interest statistics", e);
        }
    }

    public List<TraderRatio> findRatiosThrough(
            String symbol, String period, RatioType type, Instant endInclusive) {
        String sql = """
                SELECT symbol, period, ratio_type, statistic_time,
                       long_short_ratio, long_share, short_share
                FROM futures_trader_ratio
                WHERE symbol = ? AND period = ? AND ratio_type = ?
                  AND statistic_time <= ?
                ORDER BY statistic_time
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            statement.setString(2, period);
            statement.setString(3, type.name());
            statement.setObject(4, utc(endInclusive));
            try (ResultSet resultSet = statement.executeQuery()) {
                var result = new java.util.ArrayList<TraderRatio>();
                while (resultSet.next()) {
                    result.add(new TraderRatio(
                            resultSet.getString("symbol"),
                            resultSet.getString("period"),
                            RatioType.valueOf(resultSet.getString("ratio_type")),
                            resultSet.getObject("statistic_time", OffsetDateTime.class).toInstant(),
                            resultSet.getBigDecimal("long_short_ratio"),
                            resultSet.getBigDecimal("long_share"),
                            resultSet.getBigDecimal("short_share")));
                }
                return List.copyOf(result);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load trader ratios", e);
        }
    }

    private Optional<Instant> latest(String table, String symbol, String period, RatioType type) {
        String sql = "SELECT MAX(statistic_time) FROM " + table
                + " WHERE symbol = ? AND period = ?"
                + (type == null ? "" : " AND ratio_type = ?");
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            statement.setString(2, period);
            if (type != null) {
                statement.setString(3, type.name());
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                OffsetDateTime latest = resultSet.getObject(1, OffsetDateTime.class);
                return latest == null ? Optional.empty() : Optional.of(latest.toInstant());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read supporting-data cursor", e);
        }
    }

    private long count(String table, String symbol, String period, RatioType type) {
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE symbol = ? AND period = ?"
                + (type == null ? "" : " AND ratio_type = ?");
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            statement.setString(2, period);
            if (type != null) {
                statement.setString(3, type.name());
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count supporting market data", e);
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(config.url(), config.user(), config.password());
    }

    private static int commitBatch(Connection connection, PreparedStatement statement)
            throws SQLException {
        int[] results = statement.executeBatch();
        connection.commit();
        return results.length;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
