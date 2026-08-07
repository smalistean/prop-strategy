package com.smalistean.propstrategy.database;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PostgresOrderFlowFeatureRepository {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final BigDecimal ABSORPTION_MOVE = new BigDecimal("0.005");
    private static final BigDecimal STABILIZATION_MOVE = new BigDecimal("0.002");
    private final DatabaseConfig config;

    public PostgresOrderFlowFeatureRepository(DatabaseConfig config) {
        this.config = config;
    }

    public List<FeatureSnapshot> findFiveMinuteSnapshots(String symbol, Instant startInclusive,
                                                          Instant endExclusive) {
        String sql = """
                WITH minute AS (
                  SELECT k.open_time, k.close_time, k.close_price,
                    COALESCE(a.quote_notional,0) quote_notional,
                    COALESCE(a.quote_delta,0) quote_delta,
                    COALESCE(a.large_100k_buy_quote,0) large_buy,
                    COALESCE(a.large_100k_sell_quote,0) large_sell,
                    CASE WHEN a.minute_time IS NULL THEN 0 ELSE 1 END observed,
                    CASE WHEN a.reconciliation_status='MATCHED' THEN 1 ELSE 0 END exact
                  FROM futures_kline k
                  LEFT JOIN futures_agg_trade_minute a
                    ON a.symbol=k.symbol AND a.minute_time=k.open_time
                  WHERE k.symbol=? AND k.interval='1m'
                    AND k.open_time>=? - INTERVAL '240 minutes' AND k.open_time<?
                ), rolling AS (
                  SELECT *,
                    SUM(quote_notional) OVER w5 q5, SUM(quote_delta) OVER w5 d5,
                    SUM(quote_notional) OVER w15 q15, SUM(quote_delta) OVER w15 d15,
                    SUM(large_buy) OVER w15 lb15, SUM(large_sell) OVER w15 ls15,
                    SUM(quote_notional) OVER w60 q60, SUM(quote_delta) OVER w60 d60,
                    SUM(quote_notional) OVER w240 q240, SUM(quote_delta) OVER w240 d240,
                    SUM(large_buy) OVER w240 lb240, SUM(large_sell) OVER w240 ls240,
                    SUM(observed) OVER w240 observed240, SUM(exact) OVER w240 exact240,
                    LAG(close_price,5) OVER (ORDER BY open_time) close5,
                    LAG(close_price,15) OVER (ORDER BY open_time) close15
                  FROM minute
                  WINDOW w5 AS (ORDER BY open_time ROWS BETWEEN 4 PRECEDING AND CURRENT ROW),
                    w15 AS (ORDER BY open_time ROWS BETWEEN 14 PRECEDING AND CURRENT ROW),
                    w60 AS (ORDER BY open_time ROWS BETWEEN 59 PRECEDING AND CURRENT ROW),
                    w240 AS (ORDER BY open_time ROWS BETWEEN 239 PRECEDING AND CURRENT ROW)
                )
                SELECT * FROM rolling WHERE open_time>=? AND open_time<?
                  AND MOD(FLOOR(EXTRACT(EPOCH FROM open_time)/60)::BIGINT,5)=4
                ORDER BY open_time
                """;
        try (Connection connection = DriverManager.getConnection(config.url(), config.user(), config.password());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            statement.setObject(2, utc(startInclusive)); statement.setObject(3, utc(endExclusive));
            statement.setObject(4, utc(startInclusive)); statement.setObject(5, utc(endExclusive));
            try (ResultSet rows = statement.executeQuery()) {
                var result = new java.util.ArrayList<FeatureSnapshot>();
                while (rows.next()) result.add(snapshot(rows));
                return List.copyOf(result);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to calculate database order-flow features", e);
        }
    }

    private static FeatureSnapshot snapshot(ResultSet row) throws SQLException {
        BigDecimal i5 = ratio(row, "d5", "q5"), i15 = ratio(row, "d15", "q15"),
                i60 = ratio(row, "d60", "q60"), i240 = ratio(row, "d240", "q240");
        BigDecimal large15 = differenceRatio(row.getBigDecimal("lb15"), row.getBigDecimal("ls15"));
        BigDecimal large240 = differenceRatio(row.getBigDecimal("lb240"), row.getBigDecimal("ls240"));
        BigDecimal close = row.getBigDecimal("close_price");
        BigDecimal return5 = returnValue(close, row.getBigDecimal("close5"));
        BigDecimal return15 = returnValue(close, row.getBigDecimal("close15"));
        BigDecimal observed = row.getBigDecimal("observed240");
        BigDecimal coverage = observed.divide(BigDecimal.valueOf(240), MC);
        BigDecimal quality = observed.signum() == 0 ? BigDecimal.ONE
                : row.getBigDecimal("exact240").divide(observed, MC);
        Map<FeatureKey, BigDecimal> values = new HashMap<>();
        putWindow(values, 5, i5, row.getBigDecimal("d5"), BigDecimal.ZERO);
        putWindow(values, 15, i15, row.getBigDecimal("d15"), large15);
        putWindow(values, 60, i60, row.getBigDecimal("d60"), BigDecimal.ZERO);
        putWindow(values, 240, i240, row.getBigDecimal("d240"), large240);
        values.put(FeatureKey.orderFlowCoverage(240), coverage);
        values.put(FeatureKey.orderFlowQuality(240), quality);
        values.put(FeatureKey.priceReturn(5), return5);
        values.put(FeatureKey.priceReturn(15), return15);
        values.put(FeatureKey.deltaAcceleration(5, 60), i5.subtract(i60, MC));
        values.put(FeatureKey.sellAbsorption(15), absorption(i15, return15, coverage));
        values.put(FeatureKey.sellExhaustion(5, 15), exhaustion(i5, i15, return5));
        values.put(FeatureKey.priceFlowDivergence(15), normalized(return15).subtract(i15, MC));
        Instant sourceMinute = row.getObject("open_time", OffsetDateTime.class).toInstant();
        Instant sourceClose = row.getObject("close_time", OffsetDateTime.class).toInstant();
        Instant fiveMinuteOpen = sourceMinute.minusSeconds(4 * 60L);
        return new FeatureSnapshot(fiveMinuteOpen, sourceClose, sourceClose.plusMillis(1), values);
    }

    private static void putWindow(Map<FeatureKey, BigDecimal> values, int period,
                                  BigDecimal imbalance, BigDecimal delta, BigDecimal large) {
        values.put(FeatureKey.orderFlowImbalance(period), imbalance);
        values.put(FeatureKey.rollingQuoteDelta(period), delta);
        values.put(FeatureKey.largeTradeImbalance(period), large);
    }

    private static BigDecimal ratio(ResultSet row, String numerator, String denominator) throws SQLException {
        BigDecimal bottom = row.getBigDecimal(denominator);
        return bottom.signum() == 0 ? BigDecimal.ZERO : row.getBigDecimal(numerator).divide(bottom, MC);
    }

    private static BigDecimal differenceRatio(BigDecimal buy, BigDecimal sell) {
        BigDecimal total = buy.add(sell, MC);
        return total.signum() == 0 ? BigDecimal.ZERO : buy.subtract(sell, MC).divide(total, MC);
    }

    private static BigDecimal returnValue(BigDecimal current, BigDecimal previous) {
        return previous == null || previous.signum() == 0 ? BigDecimal.ZERO
                : current.subtract(previous, MC).divide(previous, MC);
    }

    private static BigDecimal absorption(BigDecimal imbalance, BigDecimal valueReturn,
                                         BigDecimal coverage) {
        BigDecimal pressure = imbalance.negate().max(BigDecimal.ZERO);
        BigDecimal resistance = BigDecimal.ONE.subtract(valueReturn.negate().max(BigDecimal.ZERO)
                .divide(ABSORPTION_MOVE, MC), MC).max(BigDecimal.ZERO).min(BigDecimal.ONE);
        return pressure.multiply(resistance, MC).multiply(coverage, MC);
    }

    private static BigDecimal exhaustion(BigDecimal fast, BigDecimal slow, BigDecimal valueReturn) {
        BigDecimal decay = slow.negate().max(BigDecimal.ZERO)
                .subtract(fast.negate().max(BigDecimal.ZERO), MC).max(BigDecimal.ZERO);
        BigDecimal stability = BigDecimal.ONE.subtract(valueReturn.abs()
                .divide(STABILIZATION_MOVE, MC), MC).max(BigDecimal.ZERO).min(BigDecimal.ONE);
        return decay.multiply(stability, MC);
    }

    private static BigDecimal normalized(BigDecimal value) {
        return value.divide(ABSORPTION_MOVE, MC).max(BigDecimal.ONE.negate()).min(BigDecimal.ONE);
    }

    private static OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
