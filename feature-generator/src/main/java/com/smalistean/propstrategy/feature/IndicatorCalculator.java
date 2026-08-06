package com.smalistean.propstrategy.feature;

import com.smalistean.propstrategy.database.Kline;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

public final class IndicatorCalculator {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    private IndicatorCalculator() {
    }

    public static BigDecimal sma(List<Kline> klines, int endIndex, int period) {
        if (period <= 0 || endIndex < period - 1) {
            throw new IllegalArgumentException("Not enough data for SMA(period=" + period + ")");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = endIndex - period + 1; i <= endIndex; i++) {
            sum = sum.add(klines.get(i).close(), MC);
        }
        return sum.divide(BigDecimal.valueOf(period), MC);
    }

    public static BigDecimal ema(List<Kline> klines, int endIndex, int period) {
        if (period <= 0 || endIndex < period - 1) {
            throw new IllegalArgumentException("Not enough data for EMA(period=" + period + ")");
        }
        BigDecimal multiplier = BigDecimal.valueOf(2)
                .divide(BigDecimal.valueOf(period + 1L), MC);
        BigDecimal ema = sma(klines, period - 1, period);
        for (int i = period; i <= endIndex; i++) {
            BigDecimal close = klines.get(i).close();
            ema = close.subtract(ema, MC).multiply(multiplier, MC).add(ema, MC);
        }
        return ema;
    }

    public static BigDecimal rsi(List<Kline> klines, int endIndex, int period) {
        if (period <= 0 || endIndex < period) {
            throw new IllegalArgumentException("Not enough data for RSI(period=" + period + ")");
        }
        BigDecimal gains = BigDecimal.ZERO;
        BigDecimal losses = BigDecimal.ZERO;
        for (int i = endIndex - period + 1; i <= endIndex; i++) {
            BigDecimal change = klines.get(i).close().subtract(klines.get(i - 1).close(), MC);
            if (change.signum() >= 0) {
                gains = gains.add(change, MC);
            } else {
                losses = losses.add(change.abs(), MC);
            }
        }
        if (losses.signum() == 0) {
            return BigDecimal.valueOf(100);
        }
        BigDecimal rs = gains.divide(losses, MC);
        return BigDecimal.valueOf(100).subtract(
                BigDecimal.valueOf(100).divide(BigDecimal.ONE.add(rs, MC), MC)
        );
    }
}
