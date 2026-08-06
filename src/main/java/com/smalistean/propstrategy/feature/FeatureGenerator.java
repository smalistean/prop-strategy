package com.smalistean.propstrategy.feature;

import com.smalistean.propstrategy.database.FundingRate;
import com.smalistean.propstrategy.database.Kline;
import com.smalistean.propstrategy.database.OpenInterestStatistic;
import com.smalistean.propstrategy.database.TraderRatio;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class FeatureGenerator {

    static final int WARMUP_CANDLES = 50;
    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public List<FeatureRow> generate(List<Kline> klines,
                                     List<FundingRate> fundingRates,
                                     List<OpenInterestStatistic> openInterest,
                                     List<TraderRatio> globalRatios,
                                     List<TraderRatio> topAccountRatios,
                                     List<TraderRatio> topPositionRatios) {
        requireChronologicalKlines(klines);
        EmaState ema20 = new EmaState(20);
        EmaState ema50 = new EmaState(50);
        WilderRsiState rsi14 = new WilderRsiState(14);
        WilderAtrState atr14 = new WilderAtrState(14);
        ContextCursor context = new ContextCursor(
                fundingRates, openInterest, globalRatios, topAccountRatios, topPositionRatios);
        List<FeatureRow> rows = new ArrayList<>();

        for (int index = 0; index < klines.size(); index++) {
            Kline candle = klines.get(index);
            BigDecimal previousClose = index == 0 ? null : klines.get(index - 1).close();
            BigDecimal currentEma20 = ema20.update(candle.close());
            BigDecimal currentEma50 = ema50.update(candle.close());
            BigDecimal currentRsi14 = rsi14.update(previousClose, candle.close());
            BigDecimal currentAtr14 = atr14.update(candle, previousClose);
            context.advanceThrough(candle.closeTime());

            if (index < WARMUP_CANDLES - 1) {
                continue;
            }
            BigDecimal range = candle.high().subtract(candle.low(), MC);
            BigDecimal body = candle.close().subtract(candle.open(), MC).abs();
            BigDecimal upperWick = candle.high().subtract(
                    candle.open().max(candle.close()), MC);
            BigDecimal lowerWick = candle.open().min(candle.close())
                    .subtract(candle.low(), MC);

            rows.add(new FeatureRow(
                    candle.openTime(),
                    candle.closeTime(),
                    candle.closeTime().plusMillis(1),
                    candle.close(),
                    percentChange(previousClose, candle.close()),
                    currentEma20,
                    currentEma50,
                    currentRsi14,
                    currentAtr14,
                    rollingReturnVolatility(klines, index, 20),
                    volumeRatio(klines, index, 20),
                    percentOfRange(body, range),
                    percentOfRange(upperWick, range),
                    percentOfRange(lowerWick, range),
                    context.openInterestChangePercent(),
                    context.fundingRate(),
                    context.globalRatio(),
                    context.topAccountRatio(),
                    context.topPositionRatio()));
        }
        return List.copyOf(rows);
    }

    private static BigDecimal rollingReturnVolatility(
            List<Kline> klines, int endIndex, int period) {
        List<BigDecimal> returns = new ArrayList<>(period);
        for (int i = endIndex - period + 1; i <= endIndex; i++) {
            returns.add(percentChange(klines.get(i - 1).close(), klines.get(i).close()));
        }
        BigDecimal mean = returns.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(period), MC);
        BigDecimal variance = BigDecimal.ZERO;
        for (BigDecimal value : returns) {
            BigDecimal difference = value.subtract(mean, MC);
            variance = variance.add(difference.multiply(difference, MC), MC);
        }
        return variance.divide(BigDecimal.valueOf(period), MC).sqrt(MC);
    }

    private static BigDecimal volumeRatio(List<Kline> klines, int endIndex, int period) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = endIndex - period + 1; i <= endIndex; i++) {
            sum = sum.add(klines.get(i).volume(), MC);
        }
        BigDecimal average = sum.divide(BigDecimal.valueOf(period), MC);
        return average.signum() == 0 ? BigDecimal.ZERO
                : klines.get(endIndex).volume().divide(average, MC);
    }

    private static BigDecimal percentChange(BigDecimal previous, BigDecimal current) {
        if (previous == null || previous.signum() == 0) {
            return null;
        }
        return current.subtract(previous, MC).divide(previous, MC).multiply(HUNDRED, MC);
    }

    private static BigDecimal percentOfRange(BigDecimal value, BigDecimal range) {
        return range.signum() == 0 ? BigDecimal.ZERO
                : value.divide(range, MC).multiply(HUNDRED, MC);
    }

    private static void requireChronologicalKlines(List<Kline> klines) {
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

    private static final class EmaState {
        private final int period;
        private final BigDecimal multiplier;
        private BigDecimal seedSum = BigDecimal.ZERO;
        private BigDecimal value;
        private int count;

        private EmaState(int period) {
            this.period = period;
            this.multiplier = BigDecimal.TWO.divide(BigDecimal.valueOf(period + 1L), MC);
        }

        private BigDecimal update(BigDecimal close) {
            count++;
            if (count <= period) {
                seedSum = seedSum.add(close, MC);
                if (count == period) {
                    value = seedSum.divide(BigDecimal.valueOf(period), MC);
                }
            } else {
                value = close.subtract(value, MC).multiply(multiplier, MC).add(value, MC);
            }
            return value;
        }
    }

    private static final class WilderRsiState {
        private final int period;
        private BigDecimal averageGain;
        private BigDecimal averageLoss;
        private int changes;

        private WilderRsiState(int period) {
            this.period = period;
        }

        private BigDecimal update(BigDecimal previousClose, BigDecimal close) {
            if (previousClose == null) {
                return null;
            }
            BigDecimal change = close.subtract(previousClose, MC);
            BigDecimal gain = change.max(BigDecimal.ZERO);
            BigDecimal loss = change.min(BigDecimal.ZERO).abs();
            changes++;
            if (changes <= period) {
                averageGain = (averageGain == null ? BigDecimal.ZERO : averageGain).add(gain, MC);
                averageLoss = (averageLoss == null ? BigDecimal.ZERO : averageLoss).add(loss, MC);
                if (changes < period) {
                    return null;
                }
                averageGain = averageGain.divide(BigDecimal.valueOf(period), MC);
                averageLoss = averageLoss.divide(BigDecimal.valueOf(period), MC);
            } else {
                BigDecimal weight = BigDecimal.valueOf(period - 1L);
                averageGain = averageGain.multiply(weight, MC).add(gain, MC)
                        .divide(BigDecimal.valueOf(period), MC);
                averageLoss = averageLoss.multiply(weight, MC).add(loss, MC)
                        .divide(BigDecimal.valueOf(period), MC);
            }
            if (averageLoss.signum() == 0) {
                return averageGain.signum() == 0 ? BigDecimal.ZERO : HUNDRED;
            }
            BigDecimal relativeStrength = averageGain.divide(averageLoss, MC);
            return HUNDRED.subtract(HUNDRED.divide(BigDecimal.ONE.add(relativeStrength, MC), MC), MC);
        }
    }

    private static final class WilderAtrState {
        private final int period;
        private BigDecimal value = BigDecimal.ZERO;
        private int count;

        private WilderAtrState(int period) {
            this.period = period;
        }

        private BigDecimal update(Kline candle, BigDecimal previousClose) {
            BigDecimal highLow = candle.high().subtract(candle.low(), MC);
            BigDecimal trueRange = previousClose == null ? highLow : highLow.max(
                    candle.high().subtract(previousClose, MC).abs()).max(
                    candle.low().subtract(previousClose, MC).abs());
            count++;
            if (count <= period) {
                value = value.add(trueRange, MC);
                if (count < period) {
                    return null;
                }
                value = value.divide(BigDecimal.valueOf(period), MC);
            } else {
                value = value.multiply(BigDecimal.valueOf(period - 1L), MC)
                        .add(trueRange, MC)
                        .divide(BigDecimal.valueOf(period), MC);
            }
            return value;
        }
    }

    private static final class ContextCursor {
        private final List<FundingRate> funding;
        private final List<OpenInterestStatistic> openInterest;
        private final List<TraderRatio> global;
        private final List<TraderRatio> topAccount;
        private final List<TraderRatio> topPosition;
        private int fundingIndex = -1;
        private int openInterestIndex = -1;
        private int globalIndex = -1;
        private int topAccountIndex = -1;
        private int topPositionIndex = -1;

        private ContextCursor(List<FundingRate> funding,
                              List<OpenInterestStatistic> openInterest,
                              List<TraderRatio> global,
                              List<TraderRatio> topAccount,
                              List<TraderRatio> topPosition) {
            this.funding = funding;
            this.openInterest = openInterest;
            this.global = global;
            this.topAccount = topAccount;
            this.topPosition = topPosition;
        }

        private void advanceThrough(Instant availableAt) {
            fundingIndex = advance(funding, fundingIndex, availableAt,
                    rate -> rate.fundingTime());
            openInterestIndex = advance(openInterest, openInterestIndex, availableAt,
                    value -> value.statisticTime());
            globalIndex = advance(global, globalIndex, availableAt,
                    value -> value.statisticTime());
            topAccountIndex = advance(topAccount, topAccountIndex, availableAt,
                    value -> value.statisticTime());
            topPositionIndex = advance(topPosition, topPositionIndex, availableAt,
                    value -> value.statisticTime());
        }

        private BigDecimal fundingRate() {
            return fundingIndex < 0 ? null : funding.get(fundingIndex).fundingRate();
        }

        private BigDecimal openInterestChangePercent() {
            if (openInterestIndex < 1) {
                return null;
            }
            return percentChange(openInterest.get(openInterestIndex - 1).sumOpenInterest(),
                    openInterest.get(openInterestIndex).sumOpenInterest());
        }

        private BigDecimal globalRatio() {
            return ratio(global, globalIndex);
        }

        private BigDecimal topAccountRatio() {
            return ratio(topAccount, topAccountIndex);
        }

        private BigDecimal topPositionRatio() {
            return ratio(topPosition, topPositionIndex);
        }

        private static BigDecimal ratio(List<TraderRatio> values, int index) {
            return index < 0 ? null : values.get(index).longShortRatio();
        }

        private static <T> int advance(List<T> values, int current, Instant availableAt,
                                       java.util.function.Function<T, Instant> timestamp) {
            int next = current + 1;
            while (next < values.size() && !timestamp.apply(values.get(next)).isAfter(availableAt)) {
                current = next++;
            }
            return current;
        }
    }
}
