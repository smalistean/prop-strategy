package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class IntradayFlatMeanReversionStrategyTest {

    private final IntradayFlatMeanReversionStrategy strategy =
            new IntradayFlatMeanReversionStrategy(
                    new IntradayFlatMeanReversionStrategy.Config(
                            20, 4, decimal("8"), 7, decimal("35"), decimal("65"),
                            14, decimal("0.25"), decimal("1.10"), decimal("1.25"),
                            decimal("0.50"), 12));

    @Test
    void entersLongWhenPriceAndRsiAreStretchedInFlatRegime() {
        List<FeatureSnapshot> history = history("100", "100", "1", "50");
        history.set(4, snapshot(4, "99", "100", "2", "30", "1.02"));

        StrategyDecision.Enter entry = assertInstanceOf(StrategyDecision.Enter.class,
                strategy.evaluate(history, 4, PositionView.flat()));

        assertEquals(Side.LONG, entry.side());
        assertEquals(0, entry.stopDistance().compareTo(decimal("2.5")));
        assertEquals(0, entry.targetDistance().compareTo(decimal("1")));
    }

    @Test
    void rejectsStretchWhenMeanIsTrendingTooQuickly() {
        List<FeatureSnapshot> history = history("100", "101", "1", "50");
        history.set(4, snapshot(4, "99", "101", "2", "30", "1.02"));

        assertEquals(StrategyDecision.hold(), strategy.evaluate(history, 4, PositionView.flat()));
    }

    private static List<FeatureSnapshot> history(String firstMean, String laterMean,
                                                 String atr, String rsi) {
        List<FeatureSnapshot> history = new ArrayList<>();
        history.add(snapshot(0, firstMean, firstMean, atr, rsi, "1"));
        for (int index = 1; index <= 4; index++) {
            history.add(snapshot(index, laterMean, laterMean, atr, rsi, "1"));
        }
        return history;
    }

    private static FeatureSnapshot snapshot(int index, String close, String ema,
                                            String atr, String rsi, String atrExpansion) {
        Instant open = Instant.parse("2026-01-01T00:00:00Z")
                .plusSeconds(index * 900L);
        Instant closeTime = open.plusSeconds(900).minusMillis(1);
        return new FeatureSnapshot(open, closeTime, closeTime.plusMillis(1), Map.of(
                FeatureKey.close(), decimal(close),
                FeatureKey.ema(20), decimal(ema),
                FeatureKey.atr(14), decimal(atr),
                FeatureKey.rsi(7), decimal(rsi),
                FeatureKey.atrExpansion(14), decimal(atrExpansion)));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
