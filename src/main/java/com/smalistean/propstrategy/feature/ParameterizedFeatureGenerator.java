package com.smalistean.propstrategy.feature;

import com.smalistean.propstrategy.database.Kline;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ParameterizedFeatureGenerator {

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public List<FeatureSnapshot> generate(List<Kline> klines, Set<FeatureKey> requiredFeatures) {
        if (requiredFeatures.isEmpty()) {
            throw new IllegalArgumentException("A strategy must require at least one feature");
        }
        validateKlines(klines);
        Map<FeatureKey, BigDecimal[]> calculated = new LinkedHashMap<>();
        for (FeatureKey key : requiredFeatures) {
            calculated.put(key, calculate(key, klines));
        }

        List<FeatureSnapshot> snapshots = new ArrayList<>();
        for (int index = 0; index < klines.size(); index++) {
            Map<FeatureKey, BigDecimal> values = new HashMap<>();
            boolean complete = true;
            for (Map.Entry<FeatureKey, BigDecimal[]> entry : calculated.entrySet()) {
                BigDecimal value = entry.getValue()[index];
                if (value == null) {
                    complete = false;
                    break;
                }
                values.put(entry.getKey(), value);
            }
            if (complete) {
                Kline candle = klines.get(index);
                snapshots.add(new FeatureSnapshot(
                        candle.openTime(), candle.closeTime(), candle.closeTime().plusMillis(1), values));
            }
        }
        return List.copyOf(snapshots);
    }

    private static BigDecimal[] calculate(FeatureKey key, List<Kline> klines) {
        return switch (key.name()) {
            case "close" -> closes(klines);
            case "ema" -> ema(klines, positivePeriod(key));
            case "rsi" -> rsi(klines, positivePeriod(key));
            case "atr" -> atr(klines, positivePeriod(key));
            default -> throw new IllegalArgumentException("Unsupported feature: " + key);
        };
    }

    private static BigDecimal[] closes(List<Kline> klines) {
        BigDecimal[] values = new BigDecimal[klines.size()];
        for (int i = 0; i < klines.size(); i++) {
            values[i] = klines.get(i).close();
        }
        return values;
    }

    private static BigDecimal[] ema(List<Kline> klines, int period) {
        BigDecimal[] values = new BigDecimal[klines.size()];
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal current = null;
        BigDecimal multiplier = BigDecimal.TWO.divide(BigDecimal.valueOf(period + 1L), MC);
        for (int i = 0; i < klines.size(); i++) {
            BigDecimal close = klines.get(i).close();
            if (i < period) {
                sum = sum.add(close, MC);
                if (i == period - 1) {
                    current = sum.divide(BigDecimal.valueOf(period), MC);
                    values[i] = current;
                }
            } else {
                current = close.subtract(current, MC).multiply(multiplier, MC).add(current, MC);
                values[i] = current;
            }
        }
        return values;
    }

    private static BigDecimal[] rsi(List<Kline> klines, int period) {
        BigDecimal[] values = new BigDecimal[klines.size()];
        BigDecimal averageGain = BigDecimal.ZERO;
        BigDecimal averageLoss = BigDecimal.ZERO;
        for (int i = 1; i < klines.size(); i++) {
            BigDecimal change = klines.get(i).close().subtract(klines.get(i - 1).close(), MC);
            BigDecimal gain = change.max(BigDecimal.ZERO);
            BigDecimal loss = change.min(BigDecimal.ZERO).abs();
            if (i <= period) {
                averageGain = averageGain.add(gain, MC);
                averageLoss = averageLoss.add(loss, MC);
                if (i == period) {
                    averageGain = averageGain.divide(BigDecimal.valueOf(period), MC);
                    averageLoss = averageLoss.divide(BigDecimal.valueOf(period), MC);
                    values[i] = rsiValue(averageGain, averageLoss);
                }
            } else {
                averageGain = wilder(averageGain, gain, period);
                averageLoss = wilder(averageLoss, loss, period);
                values[i] = rsiValue(averageGain, averageLoss);
            }
        }
        return values;
    }

    private static BigDecimal[] atr(List<Kline> klines, int period) {
        BigDecimal[] values = new BigDecimal[klines.size()];
        BigDecimal current = BigDecimal.ZERO;
        for (int i = 0; i < klines.size(); i++) {
            Kline candle = klines.get(i);
            BigDecimal highLow = candle.high().subtract(candle.low(), MC);
            BigDecimal trueRange = i == 0 ? highLow : highLow.max(
                    candle.high().subtract(klines.get(i - 1).close(), MC).abs()).max(
                    candle.low().subtract(klines.get(i - 1).close(), MC).abs());
            if (i < period) {
                current = current.add(trueRange, MC);
                if (i == period - 1) {
                    current = current.divide(BigDecimal.valueOf(period), MC);
                    values[i] = current;
                }
            } else {
                current = wilder(current, trueRange, period);
                values[i] = current;
            }
        }
        return values;
    }

    private static BigDecimal wilder(BigDecimal average, BigDecimal current, int period) {
        return average.multiply(BigDecimal.valueOf(period - 1L), MC).add(current, MC)
                .divide(BigDecimal.valueOf(period), MC);
    }

    private static BigDecimal rsiValue(BigDecimal averageGain, BigDecimal averageLoss) {
        if (averageLoss.signum() == 0) {
            return averageGain.signum() == 0 ? BigDecimal.ZERO : HUNDRED;
        }
        BigDecimal relativeStrength = averageGain.divide(averageLoss, MC);
        return HUNDRED.subtract(HUNDRED.divide(BigDecimal.ONE.add(relativeStrength, MC), MC), MC);
    }

    private static int positivePeriod(FeatureKey key) {
        if (key.period() <= 0) {
            throw new IllegalArgumentException("Feature requires a positive period: " + key);
        }
        return key.period();
    }

    private static void validateKlines(List<Kline> klines) {
        for (int i = 0; i < klines.size(); i++) {
            Kline candle = klines.get(i);
            if (candle.closeTime() == null) {
                throw new IllegalArgumentException("Feature candles require closeTime");
            }
            if (i > 0 && !candle.openTime().isAfter(klines.get(i - 1).openTime())) {
                throw new IllegalArgumentException("Feature candles must be strictly chronological");
            }
        }
    }
}
