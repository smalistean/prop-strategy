package com.smalistean.propstrategy.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

public final class PostgresBookDepthSecondRepository {

    private static final String UPSERT = """
            INSERT INTO binance_book_depth_second (
              symbol, second_time, snapshots, mean_bid_qty_1, mean_ask_qty_1,
              min_bid_qty_1, min_ask_qty_1, mean_bid_notional, mean_ask_notional,
              mean_bid_span_bps, mean_ask_span_bps
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (symbol, second_time) DO UPDATE SET
              snapshots=EXCLUDED.snapshots, mean_bid_qty_1=EXCLUDED.mean_bid_qty_1,
              mean_ask_qty_1=EXCLUDED.mean_ask_qty_1, min_bid_qty_1=EXCLUDED.min_bid_qty_1,
              min_ask_qty_1=EXCLUDED.min_ask_qty_1, mean_bid_notional=EXCLUDED.mean_bid_notional,
              mean_ask_notional=EXCLUDED.mean_ask_notional,
              mean_bid_span_bps=EXCLUDED.mean_bid_span_bps,
              mean_ask_span_bps=EXCLUDED.mean_ask_span_bps
            """;

    private final DatabaseConfig config;

    public PostgresBookDepthSecondRepository(DatabaseConfig config) {
        this.config = config;
    }

    public int upsertAll(List<BookDepthSecond> seconds) {
        if (seconds.isEmpty()) {
            return 0;
        }
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
                for (BookDepthSecond s : seconds) {
                    int i = 1;
                    statement.setString(i++, s.symbol());
                    statement.setObject(i++, utc(s.secondTime()));
                    statement.setInt(i++, s.snapshots());
                    statement.setBigDecimal(i++, s.meanBidQty1());
                    statement.setBigDecimal(i++, s.meanAskQty1());
                    statement.setBigDecimal(i++, s.minBidQty1());
                    statement.setBigDecimal(i++, s.minAskQty1());
                    statement.setBigDecimal(i++, s.meanBidNotional());
                    statement.setBigDecimal(i++, s.meanAskNotional());
                    statement.setBigDecimal(i++, s.meanBidSpanBps());
                    statement.setBigDecimal(i, s.meanAskSpanBps());
                    statement.addBatch();
                }
                int[] result = statement.executeBatch();
                connection.commit();
                return result.length;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to upsert book-depth seconds", e);
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(config.url(), config.user(), config.password());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
