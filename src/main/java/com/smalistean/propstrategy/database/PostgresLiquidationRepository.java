package com.smalistean.propstrategy.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

public final class PostgresLiquidationRepository {

    // DO NOTHING rather than DO UPDATE: a re-delivered frame after a reconnect is the identical
    // event, so there is nothing to refresh, and silently ignoring it keeps the count honest.
    private static final String INSERT = """
            INSERT INTO binance_liquidation (
              symbol, event_time, trade_time, side, order_type, time_in_force, order_status,
              quantity, price, average_price, last_filled_qty, filled_accum_qty, notional
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (symbol, trade_time, side, price, quantity, filled_accum_qty) DO NOTHING
            """;

    private final DatabaseConfig config;

    public PostgresLiquidationRepository(DatabaseConfig config) {
        this.config = config;
    }

    public int insertAll(List<Liquidation> liquidations) {
        if (liquidations.isEmpty()) {
            return 0;
        }
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                for (Liquidation l : liquidations) {
                    int i = 1;
                    statement.setString(i++, l.symbol());
                    statement.setObject(i++, utc(l.eventTime()));
                    statement.setObject(i++, utc(l.tradeTime()));
                    statement.setString(i++, l.side());
                    statement.setString(i++, l.orderType());
                    statement.setString(i++, l.timeInForce());
                    statement.setString(i++, l.orderStatus());
                    statement.setBigDecimal(i++, l.quantity());
                    statement.setBigDecimal(i++, l.price());
                    statement.setBigDecimal(i++, l.averagePrice());
                    statement.setBigDecimal(i++, l.lastFilledQty());
                    statement.setBigDecimal(i++, l.filledAccumQty());
                    statement.setBigDecimal(i, l.notional());
                    statement.addBatch();
                }
                int[] result = statement.executeBatch();
                connection.commit();
                int inserted = 0;
                for (int r : result) {
                    if (r > 0) {
                        inserted++;
                    }
                }
                return inserted;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert liquidations", e);
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(config.url(), config.user(), config.password());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
