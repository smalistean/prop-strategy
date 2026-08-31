package com.smalistean.propstrategy.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

public final class PostgresBookTickerSecondRepository {

    private static final String UPSERT = """
            INSERT INTO binance_book_ticker_second (
              symbol, second_time, updates, open_bid, open_ask, close_bid, close_ask,
              min_bid, max_bid, min_ask, max_ask, close_bid_qty, close_ask_qty,
              mean_spread_bps, min_spread_bps, max_spread_bps
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (symbol, second_time) DO UPDATE SET
              updates=EXCLUDED.updates, open_bid=EXCLUDED.open_bid, open_ask=EXCLUDED.open_ask,
              close_bid=EXCLUDED.close_bid, close_ask=EXCLUDED.close_ask,
              min_bid=EXCLUDED.min_bid, max_bid=EXCLUDED.max_bid,
              min_ask=EXCLUDED.min_ask, max_ask=EXCLUDED.max_ask,
              close_bid_qty=EXCLUDED.close_bid_qty, close_ask_qty=EXCLUDED.close_ask_qty,
              mean_spread_bps=EXCLUDED.mean_spread_bps, min_spread_bps=EXCLUDED.min_spread_bps,
              max_spread_bps=EXCLUDED.max_spread_bps
            """;

    private final DatabaseConfig config;

    public PostgresBookTickerSecondRepository(DatabaseConfig config) {
        this.config = config;
    }

    public int upsertAll(List<BookTickerSecond> seconds) {
        if (seconds.isEmpty()) {
            return 0;
        }
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
                for (BookTickerSecond s : seconds) {
                    int i = 1;
                    statement.setString(i++, s.symbol());
                    statement.setObject(i++, utc(s.secondTime()));
                    statement.setInt(i++, s.updates());
                    statement.setBigDecimal(i++, s.openBid());
                    statement.setBigDecimal(i++, s.openAsk());
                    statement.setBigDecimal(i++, s.closeBid());
                    statement.setBigDecimal(i++, s.closeAsk());
                    statement.setBigDecimal(i++, s.minBid());
                    statement.setBigDecimal(i++, s.maxBid());
                    statement.setBigDecimal(i++, s.minAsk());
                    statement.setBigDecimal(i++, s.maxAsk());
                    statement.setBigDecimal(i++, s.closeBidQty());
                    statement.setBigDecimal(i++, s.closeAskQty());
                    statement.setBigDecimal(i++, s.meanSpreadBps());
                    statement.setBigDecimal(i++, s.minSpreadBps());
                    statement.setBigDecimal(i, s.maxSpreadBps());
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
            throw new IllegalStateException("Failed to upsert book-ticker seconds", e);
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(config.url(), config.user(), config.password());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
