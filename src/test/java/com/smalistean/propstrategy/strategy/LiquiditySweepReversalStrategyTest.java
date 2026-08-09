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

class LiquiditySweepReversalStrategyTest {
    private final LiquiditySweepReversalStrategy strategy = new LiquiditySweepReversalStrategy(
            new LiquiditySweepReversalStrategy.Config(20, 1, 2, 14, 20, 3,
                    new BigDecimal("0.25"), new BigDecimal("0.25"), new BigDecimal("1.2"),
                    new BigDecimal("0.25"), BigDecimal.valueOf(3), 96));

    @Test
    void entersLongOnlyAfterUnsweptSupportIsSweptReclaimedAndStructurallyBroken() {
        List<FeatureSnapshot> history = history();
        history.set(28, snapshot(28, "99", "100", "98", "100", "99", "1.0"));
        history.set(29, snapshot(29, "99", "102", "100", "102", "99", "1.5"));

        StrategyDecision.EnterAtLevels entry = assertInstanceOf(StrategyDecision.EnterAtLevels.class,
                strategy.evaluate(history, 29, PositionView.flat()));

        assertEquals(Side.LONG, entry.side());
        assertEquals(0, entry.stopPrice().compareTo(new BigDecimal("97.5")));
        assertEquals(0, entry.targetPrice().compareTo(new BigDecimal("120")));
    }

    @Test
    void rejectsSweepWhenConfirmationVolumeIsTooLow() {
        List<FeatureSnapshot> history = history();
        history.set(28, snapshot(28, "99", "100", "98", "100", "99", "1.0"));
        history.set(29, snapshot(29, "99", "102", "100", "102", "99", "1.1"));

        assertEquals(StrategyDecision.hold(), strategy.evaluate(history, 29, PositionView.flat()));
    }

    private static List<FeatureSnapshot> history() {
        List<FeatureSnapshot> result = new ArrayList<>();
        for (int i = 0; i < 30; i++) result.add(snapshot(i, "100", "101", "100", "100", "100", "1.5"));
        result.set(10, snapshot(10, "100", "101", "99", "100", "100", "1.5"));
        result.set(16, snapshot(16, "100", "101", "99", "100", "100", "1.5"));
        result.set(12, snapshot(12, "100", "120", "100", "100", "100", "1.5"));
        result.set(22, snapshot(22, "100", "120", "100", "100", "100", "1.5"));
        return result;
    }

    private static FeatureSnapshot snapshot(int index, String open, String high, String low,
                                            String close, String ignored, String volumeRatio) {
        Instant time = Instant.parse("2024-01-01T00:00:00Z").plusSeconds(index * 900L);
        return new FeatureSnapshot(time, time, time, Map.of(
                FeatureKey.open(), new BigDecimal(open), FeatureKey.high(), new BigDecimal(high),
                FeatureKey.low(), new BigDecimal(low), FeatureKey.close(), new BigDecimal(close),
                FeatureKey.atr(14), BigDecimal.TWO, FeatureKey.volumeRatio(20), new BigDecimal(volumeRatio)));
    }
}
