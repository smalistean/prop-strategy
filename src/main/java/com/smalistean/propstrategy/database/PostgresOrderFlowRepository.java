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

public final class PostgresOrderFlowRepository {

    private final DatabaseConfig config;

    public PostgresOrderFlowRepository(DatabaseConfig config) {
        this.config = config;
    }

    public List<OrderFlowMinute> findRange(String symbol, Instant startInclusive,
                                           Instant endExclusive) {
        String sql = """
                SELECT minute_time, quote_notional, quote_delta,
                       large_100k_buy_quote, large_100k_sell_quote,
                       reconciliation_status
                FROM futures_agg_trade_minute
                WHERE symbol=? AND minute_time>=? AND minute_time<?
                ORDER BY minute_time
                """;
        try (Connection connection = DriverManager.getConnection(config.url(), config.user(), config.password());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            statement.setObject(2, OffsetDateTime.ofInstant(startInclusive, ZoneOffset.UTC));
            statement.setObject(3, OffsetDateTime.ofInstant(endExclusive, ZoneOffset.UTC));
            try (ResultSet result = statement.executeQuery()) {
                var rows = new java.util.ArrayList<OrderFlowMinute>();
                while (result.next()) {
                    rows.add(new OrderFlowMinute(
                            result.getObject("minute_time", OffsetDateTime.class).toInstant(),
                            result.getBigDecimal("quote_notional"),
                            result.getBigDecimal("quote_delta"),
                            result.getBigDecimal("large_100k_buy_quote"),
                            result.getBigDecimal("large_100k_sell_quote"),
                            result.getString("reconciliation_status").equals("MATCHED")));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load order-flow minutes", e);
        }
    }
}
