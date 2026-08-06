package com.smalistean.propstrategy.feature;

import com.smalistean.propstrategy.database.Kline;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParameterizedFeatureGeneratorTest {

    @Test
    void calculatesArbitraryStrategyPeriodsAndSkipsTheirWarmup() {
        List<Kline> candles = candles(10);
        Set<FeatureKey> keys = Set.of(
                FeatureKey.close(), FeatureKey.ema(3), FeatureKey.ema(5),
                FeatureKey.rsi(4), FeatureKey.atr(2));

        List<FeatureSnapshot> snapshots = new ParameterizedFeatureGenerator()
                .generate(candles, keys);

        assertEquals(6, snapshots.size());
        FeatureSnapshot first = snapshots.getFirst();
        assertEquals(candles.get(4).openTime(), first.candleOpenTime());
        assertEquals(0, first.require(FeatureKey.ema(5)).compareTo(new BigDecimal("103")));
        assertEquals(0, first.require(FeatureKey.rsi(4)).compareTo(new BigDecimal("100")));
        assertTrue(first.require(FeatureKey.atr(2)).signum() > 0);
    }

    @Test
    void channelAndVolumeFeaturesExcludeCurrentCandleFromTheirLookback() {
        List<Kline> candles = candles(6);
        FeatureKey high = FeatureKey.rollingHigh(3);
        FeatureKey low = FeatureKey.rollingLow(3);
        FeatureKey volume = FeatureKey.volumeRatio(3);

        FeatureSnapshot snapshot = new ParameterizedFeatureGenerator()
                .generate(candles, Set.of(high, low, volume)).getFirst();

        assertEquals(candles.get(3).openTime(), snapshot.candleOpenTime());
        assertEquals(0, snapshot.require(high).compareTo(candles.get(2).high()));
        assertEquals(0, snapshot.require(low).compareTo(candles.get(0).low()));
        BigDecimal priorAverage = candles.subList(0, 3).stream()
                .map(Kline::volume).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(3));
        assertEquals(0, snapshot.require(volume)
                .compareTo(candles.get(3).volume().divide(priorAverage, java.math.MathContext.DECIMAL64)));
    }

    @Test
    void compressionPercentileUsesCompletedCandleBeforeBreakoutCandle() {
        List<Kline> normal = candles(8);
        List<Kline> breakout = new ArrayList<>(normal);
        Kline current = breakout.get(7);
        breakout.set(7, new Kline(current.openTime(), current.open(), new BigDecimal("1000"),
                current.low(), new BigDecimal("900"), current.volume(), current.closeTime(),
                current.quoteAssetVolume(), current.tradeCount(),
                current.takerBuyBaseVolume(), current.takerBuyQuoteVolume()));
        FeatureKey compression = FeatureKey.priorBollingerBandwidthPercentile(3, 3);

        BigDecimal normalValue = new ParameterizedFeatureGenerator()
                .generate(normal, Set.of(compression)).getLast().require(compression);
        BigDecimal breakoutValue = new ParameterizedFeatureGenerator()
                .generate(breakout, Set.of(compression)).getLast().require(compression);

        assertEquals(0, normalValue.compareTo(breakoutValue));
    }

    private static List<Kline> candles(int count) {
        Instant start = Instant.parse("2026-08-01T00:00:00Z");
        List<Kline> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Instant openTime = start.plus(Duration.ofMinutes(15L * i));
            BigDecimal price = BigDecimal.valueOf(101 + i);
            result.add(new Kline(openTime, price, price.add(BigDecimal.ONE),
                    price.subtract(BigDecimal.ONE), price, BigDecimal.ONE,
                    openTime.plus(Duration.ofMinutes(15)).minusMillis(1),
                    BigDecimal.ZERO, 1, BigDecimal.ZERO, BigDecimal.ZERO));
        }
        return result;
    }
}
