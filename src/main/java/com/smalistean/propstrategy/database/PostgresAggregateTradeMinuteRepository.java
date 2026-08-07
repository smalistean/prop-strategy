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
import java.util.OptionalLong;

public final class PostgresAggregateTradeMinuteRepository {

    private static final String UPSERT = """
            INSERT INTO futures_agg_trade_minute (
              symbol, minute_time, first_event_time, last_event_time, first_agg_trade_id,
              last_agg_trade_id, aggregate_trade_count, underlying_trade_count, base_volume,
              quote_notional, aggressive_buy_base, aggressive_sell_base, aggressive_buy_quote,
              aggressive_sell_quote, base_delta, quote_delta, first_price, last_price,
              minimum_price, maximum_price, buy_vwap, sell_vwap, max_aggregate_quote,
              large_10k_count, large_10k_buy_quote, large_10k_sell_quote, large_100k_count,
              large_100k_buy_quote, large_100k_sell_quote, large_1m_count,
              large_1m_buy_quote, large_1m_sell_quote, agg_trade_id_gap_count, duplicate_count
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (symbol, minute_time) DO UPDATE SET
              first_event_time=EXCLUDED.first_event_time, last_event_time=EXCLUDED.last_event_time,
              first_agg_trade_id=EXCLUDED.first_agg_trade_id, last_agg_trade_id=EXCLUDED.last_agg_trade_id,
              aggregate_trade_count=EXCLUDED.aggregate_trade_count,
              underlying_trade_count=EXCLUDED.underlying_trade_count, base_volume=EXCLUDED.base_volume,
              quote_notional=EXCLUDED.quote_notional, aggressive_buy_base=EXCLUDED.aggressive_buy_base,
              aggressive_sell_base=EXCLUDED.aggressive_sell_base,
              aggressive_buy_quote=EXCLUDED.aggressive_buy_quote,
              aggressive_sell_quote=EXCLUDED.aggressive_sell_quote, base_delta=EXCLUDED.base_delta,
              quote_delta=EXCLUDED.quote_delta, first_price=EXCLUDED.first_price,
              last_price=EXCLUDED.last_price, minimum_price=EXCLUDED.minimum_price,
              maximum_price=EXCLUDED.maximum_price, buy_vwap=EXCLUDED.buy_vwap,
              sell_vwap=EXCLUDED.sell_vwap, max_aggregate_quote=EXCLUDED.max_aggregate_quote,
              large_10k_count=EXCLUDED.large_10k_count,
              large_10k_buy_quote=EXCLUDED.large_10k_buy_quote,
              large_10k_sell_quote=EXCLUDED.large_10k_sell_quote,
              large_100k_count=EXCLUDED.large_100k_count,
              large_100k_buy_quote=EXCLUDED.large_100k_buy_quote,
              large_100k_sell_quote=EXCLUDED.large_100k_sell_quote,
              large_1m_count=EXCLUDED.large_1m_count,
              large_1m_buy_quote=EXCLUDED.large_1m_buy_quote,
              large_1m_sell_quote=EXCLUDED.large_1m_sell_quote,
              agg_trade_id_gap_count=EXCLUDED.agg_trade_id_gap_count,
              duplicate_count=EXCLUDED.duplicate_count, updated_at=NOW()
            """;
    private final DatabaseConfig config;

    public PostgresAggregateTradeMinuteRepository(DatabaseConfig config) {
        this.config = config;
    }

    public int upsertAll(List<AggregateTradeMinute> minutes) {
        if (minutes.isEmpty()) return 0;
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
                for (AggregateTradeMinute m : minutes) {
                    int i = 1;
                    statement.setString(i++, m.symbol());
                    statement.setObject(i++, utc(m.minuteTime())); statement.setObject(i++, utc(m.firstEventTime()));
                    statement.setObject(i++, utc(m.lastEventTime())); statement.setLong(i++, m.firstAggregateTradeId());
                    statement.setLong(i++, m.lastAggregateTradeId()); statement.setInt(i++, m.aggregateTradeCount());
                    statement.setLong(i++, m.underlyingTradeCount()); statement.setBigDecimal(i++, m.baseVolume());
                    statement.setBigDecimal(i++, m.quoteNotional()); statement.setBigDecimal(i++, m.aggressiveBuyBase());
                    statement.setBigDecimal(i++, m.aggressiveSellBase()); statement.setBigDecimal(i++, m.aggressiveBuyQuote());
                    statement.setBigDecimal(i++, m.aggressiveSellQuote()); statement.setBigDecimal(i++, m.baseDelta());
                    statement.setBigDecimal(i++, m.quoteDelta()); statement.setBigDecimal(i++, m.firstPrice());
                    statement.setBigDecimal(i++, m.lastPrice()); statement.setBigDecimal(i++, m.minimumPrice());
                    statement.setBigDecimal(i++, m.maximumPrice()); statement.setBigDecimal(i++, m.buyVwap());
                    statement.setBigDecimal(i++, m.sellVwap()); statement.setBigDecimal(i++, m.maximumAggregateQuote());
                    statement.setInt(i++, m.large10kCount()); statement.setBigDecimal(i++, m.large10kBuyQuote());
                    statement.setBigDecimal(i++, m.large10kSellQuote()); statement.setInt(i++, m.large100kCount());
                    statement.setBigDecimal(i++, m.large100kBuyQuote()); statement.setBigDecimal(i++, m.large100kSellQuote());
                    statement.setInt(i++, m.large1mCount()); statement.setBigDecimal(i++, m.large1mBuyQuote());
                    statement.setBigDecimal(i++, m.large1mSellQuote()); statement.setLong(i++, m.aggregateTradeIdGapCount());
                    statement.setInt(i, m.duplicateCount()); statement.addBatch();
                }
                int[] result = statement.executeBatch(); connection.commit(); return result.length;
            } catch (SQLException e) { connection.rollback(); throw e; }
        } catch (SQLException e) { throw new IllegalStateException("Failed to upsert aggregate-trade minutes", e); }
    }

    public void reconcile(String symbol, Instant start, Instant end) {
        String sql = """
                UPDATE futures_agg_trade_minute a SET
                  kline_base_volume_difference = a.base_volume - k.volume,
                  reconciliation_status = CASE WHEN ABS(a.base_volume - k.volume) <= 0.00000001
                    THEN 'MATCHED' ELSE 'MISMATCH' END, updated_at=NOW()
                FROM futures_kline k
                WHERE a.symbol=? AND a.minute_time>=? AND a.minute_time<?
                  AND k.symbol=a.symbol AND k.interval='1m' AND k.open_time=a.minute_time
                """;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol); statement.setObject(2, utc(start)); statement.setObject(3, utc(end));
            statement.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("Failed to reconcile aggregate trades", e); }
    }

    public void recordCompletedArchive(String archiveName, String sourceUrl, String sha256,
                                       long archiveSize, int minuteRows, long sourceRows) {
        String sql = """
                INSERT INTO futures_agg_trade_import
                  (archive_name, source_url, expected_sha256, archive_size, status,
                   minute_rows, source_rows, completed_at)
                VALUES (?, ?, ?, ?, 'COMPLETED', ?, ?, NOW())
                ON CONFLICT (archive_name) DO UPDATE SET source_url=EXCLUDED.source_url,
                  expected_sha256=EXCLUDED.expected_sha256, archive_size=EXCLUDED.archive_size,
                  status='COMPLETED', minute_rows=EXCLUDED.minute_rows,
                  source_rows=EXCLUDED.source_rows, completed_at=NOW(), updated_at=NOW()
                """;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, archiveName); statement.setString(2, sourceUrl);
            statement.setString(3, sha256); statement.setLong(4, archiveSize);
            statement.setInt(5, minuteRows); statement.setLong(6, sourceRows);
            statement.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("Failed to record archive completion", e); }
    }

    public boolean archiveCompleted(String archiveName, String sha256) {
        String sql = "SELECT 1 FROM futures_agg_trade_import WHERE archive_name=? "
                + "AND expected_sha256=? AND status='COMPLETED'";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, archiveName); statement.setString(2, sha256);
            try (java.sql.ResultSet result = statement.executeQuery()) { return result.next(); }
        } catch (SQLException e) { throw new IllegalStateException("Failed to read archive status", e); }
    }

    public OptionalLong latestAggregateTradeIdBefore(String symbol, Instant boundary) {
        String sql = "SELECT MAX(last_agg_trade_id) FROM futures_agg_trade_minute WHERE symbol=? AND minute_time<?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            statement.setObject(2, utc(boundary));
            try (java.sql.ResultSet result = statement.executeQuery()) {
                result.next(); long value = result.getLong(1);
                return result.wasNull() ? OptionalLong.empty() : OptionalLong.of(value);
            }
        } catch (SQLException e) { throw new IllegalStateException("Failed to read latest aggregate-trade ID", e); }
    }

    public long count(String symbol, Instant start, Instant end) {
        String sql = "SELECT COUNT(*) FROM futures_agg_trade_minute WHERE symbol=? AND minute_time>=? AND minute_time<?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol); statement.setObject(2, utc(start)); statement.setObject(3, utc(end));
            try (java.sql.ResultSet result = statement.executeQuery()) { result.next(); return result.getLong(1); }
        } catch (SQLException e) { throw new IllegalStateException("Failed to count aggregate-trade minutes", e); }
    }

    /**
     * Exposes aggregate-trade OHLC as minute bars for strict maker trade-through.
     * The close time is the last actual aggregate trade, not the calendar minute end.
     */
    public List<Kline> findExecutionRange(String symbol, Instant start, Instant end) {
        String sql = """
                SELECT minute_time, first_price, maximum_price, minimum_price, last_price,
                       base_volume, quote_notional, aggregate_trade_count,
                       aggressive_buy_base, aggressive_buy_quote, last_event_time
                FROM futures_agg_trade_minute
                WHERE symbol=? AND minute_time>=? AND minute_time<?
                ORDER BY minute_time
                """;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            statement.setObject(2, utc(start));
            statement.setObject(3, utc(end));
            try (ResultSet rows = statement.executeQuery()) {
                List<Kline> result = new java.util.ArrayList<>();
                while (rows.next()) {
                    result.add(new Kline(
                            rows.getObject("minute_time", OffsetDateTime.class).toInstant(),
                            rows.getBigDecimal("first_price"), rows.getBigDecimal("maximum_price"),
                            rows.getBigDecimal("minimum_price"), rows.getBigDecimal("last_price"),
                            rows.getBigDecimal("base_volume"),
                            rows.getObject("last_event_time", OffsetDateTime.class).toInstant(),
                            rows.getBigDecimal("quote_notional"), rows.getInt("aggregate_trade_count"),
                            rows.getBigDecimal("aggressive_buy_base"),
                            rows.getBigDecimal("aggressive_buy_quote")));
                }
                return List.copyOf(result);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load aggregate-trade execution range", e);
        }
    }

    private Connection open() throws SQLException { return DriverManager.getConnection(config.url(), config.user(), config.password()); }
    private static OffsetDateTime utc(Instant instant) { return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC); }
}
