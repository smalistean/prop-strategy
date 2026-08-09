package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ApolloOrderedLiquiditySequenceStrategyTest {
    private final ApolloOrderedLiquiditySequenceStrategy strategy =
            new ApolloOrderedLiquiditySequenceStrategy(
                    new ApolloOrderedLiquiditySequenceStrategy.Config(14, 20, 4, 2, 1, 2, 5,
                            new BigDecimal("0.15"), new BigDecimal("0.10"), new BigDecimal("0.20"),
                            new BigDecimal("1.20"), new BigDecimal("0.25"),
                            BigDecimal.valueOf(3), 96, false));

    @Test
    void entersOnlyAfterFreshSweepReclaimAndSeparateLocalBreak() {
        List<FeatureSnapshot> history = history();
        history.set(7, snapshot(7, "101", "102", "98", "99", "1.0"));
        history.set(8, snapshot(8, "99", "102", "99", "101", "1.0"));
        history.set(9, snapshot(9, "101", "103", "100", "102", "1.0"));
        history.set(10, snapshot(10, "102", "105", "101", "104", "1.5"));

        StrategyDecision.EnterAtLevels entry = assertInstanceOf(StrategyDecision.EnterAtLevels.class,
                strategy.evaluate(history, 10, PositionView.flat()));

        assertEquals(Side.LONG, entry.side());
        assertEquals(0, entry.stopPrice().compareTo(new BigDecimal("97.5")));
        assertEquals(0, entry.targetPrice().compareTo(new BigDecimal("130")));
    }

    @Test
    void rejectsASecondTouchBeforeTheSweep() {
        List<FeatureSnapshot> history = history();
        history.set(3, snapshot(3, "101", "102", "100", "101", "1.5"));
        history.set(7, snapshot(7, "101", "102", "98", "99", "1.0"));
        history.set(8, snapshot(8, "99", "102", "99", "101", "1.0"));
        history.set(9, snapshot(9, "101", "103", "100", "102", "1.0"));
        history.set(10, snapshot(10, "102", "105", "101", "104", "1.5"));

        assertEquals(StrategyDecision.hold(), strategy.evaluate(history, 10, PositionView.flat()));
    }

    private static List<FeatureSnapshot> history() {
        List<FeatureSnapshot> history = new ArrayList<>();
        for (int i = 0; i < 11; i++) history.add(snapshot(i, "101", "102", "101", "101", "1.5"));
        return history;
    }

    private static FeatureSnapshot snapshot(int index, String open, String high, String low,
                                            String close, String volumeRatio) {
        Instant time = Instant.parse("2024-01-01T00:00:00Z").plusSeconds(index * 900L);
        return new FeatureSnapshot(time, time, time, Map.of(
                FeatureKey.open(), new BigDecimal(open), FeatureKey.high(), new BigDecimal(high),
                FeatureKey.low(), new BigDecimal(low), FeatureKey.close(), new BigDecimal(close),
                FeatureKey.atr(14), BigDecimal.TWO, FeatureKey.volumeRatio(20),
                new BigDecimal(volumeRatio), FeatureKey.higherTimeframeSupport(), new BigDecimal("100"),
                FeatureKey.higherTimeframeResistance(), new BigDecimal("130")));
    }
}
