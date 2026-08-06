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
