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

class StructuralChannelStrategyTest {

    private final StructuralChannelStrategy strategy = new StructuralChannelStrategy(
            new StructuralChannelStrategy.Config(96, 14, 2, decimal("0.25"),
                    decimal("0.35"), decimal("0.25"), decimal("0.25"),
                    decimal("6"), decimal("3"), 96));

    @Test
    void entersLongWithStopBeyondSupportAndTargetInsideResistance() {
        List<FeatureSnapshot> history = channelHistory("100.20", "100", "110", "1");

        StrategyDecision.EnterAtLevels entry = assertInstanceOf(StrategyDecision.EnterAtLevels.class,
                strategy.evaluate(history, 96, PositionView.flat()));

        assertEquals(Side.LONG, entry.side());
        assertEquals(0, entry.stopPrice().compareTo(decimal("99.75")));
        assertEquals(0, entry.targetPrice().compareTo(decimal("109.75")));
    }

    @Test
    void rejectsChannelThatIsTooNarrowForStructuralRisk() {
        List<FeatureSnapshot> history = channelHistory("100.30", "100", "102", "4");

        assertEquals(StrategyDecision.hold(), strategy.evaluate(history, 96, PositionView.flat()));
    }

    private static List<FeatureSnapshot> channelHistory(String currentClose, String lower,
                                                        String upper, String atr) {
        List<FeatureSnapshot> result = new ArrayList<>();
        for (int i = 0; i <= 96; i++) {
            String low = i == 0 || i == 20 ? lower : "104";
            String high = i == 10 || i == 30 ? upper : "106";
            result.add(snapshot(i, i == 96 ? currentClose : "105", high, low,
                    lower, upper, atr));
        }
        return result;
    }

    private static FeatureSnapshot snapshot(int index, String close, String high, String low,
                                            String channelLow, String channelHigh, String atr) {
        Instant open = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(index * 900L);
        Instant closeTime = open.plusSeconds(900).minusMillis(1);
        return new FeatureSnapshot(open, closeTime, closeTime.plusMillis(1), Map.of(
                FeatureKey.close(), decimal(close), FeatureKey.high(), decimal(high),
                FeatureKey.low(), decimal(low), FeatureKey.rollingLow(96), decimal(channelLow),
                FeatureKey.rollingHigh(96), decimal(channelHigh),
                FeatureKey.atr(14), decimal(atr)));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
