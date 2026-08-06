package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class DonchianBreakoutStrategyTest {

    private final DonchianBreakoutStrategy strategy = new DonchianBreakoutStrategy(
            new DonchianBreakoutStrategy.Config(
                    40, 20, 20, new BigDecimal("1.25"), 14,
                    new BigDecimal("2"), new BigDecimal("3"), 96));

    @Test
    void entersLongOnlyAfterConfirmedPriorChannelBreakoutAndVolume() {
        StrategyDecision.Enter decision = assertInstanceOf(StrategyDecision.Enter.class,
                strategy.evaluate(List.of(snapshot("111", "110", "90", "105", "95", "1.30", "4")),
                        0, PositionView.flat()));

        assertEquals(Side.LONG, decision.side());
        assertEquals(0, decision.stopDistance().compareTo(new BigDecimal("8")));
        assertEquals(0, decision.targetDistance().compareTo(new BigDecimal("24")));
    }

    @Test
    void exitsLongAfterCloseBreaksExitChannel() {
        PositionView longPosition = new PositionView(Side.LONG, Instant.EPOCH,
                BigDecimal.valueOf(100), BigDecimal.ONE, 5);

        StrategyDecision.Exit decision = assertInstanceOf(StrategyDecision.Exit.class,
                strategy.evaluate(List.of(snapshot("94", "110", "90", "105", "95", "1", "4")),
                        0, longPosition));

        assertEquals("Donchian channel exit", decision.reason());
    }

    private static FeatureSnapshot snapshot(String close, String entryHigh, String entryLow,
                                            String exitHigh, String exitLow,
                                            String volumeRatio, String atr) {
        Instant closeTime = Instant.parse("2026-01-01T00:14:59.999Z");
        return new FeatureSnapshot(Instant.parse("2026-01-01T00:00:00Z"), closeTime,
                closeTime.plusMillis(1), Map.of(
                FeatureKey.close(), new BigDecimal(close),
                FeatureKey.rollingHigh(40), new BigDecimal(entryHigh),
                FeatureKey.rollingLow(40), new BigDecimal(entryLow),
                FeatureKey.rollingHigh(20), new BigDecimal(exitHigh),
                FeatureKey.rollingLow(20), new BigDecimal(exitLow),
                FeatureKey.volumeRatio(20), new BigDecimal(volumeRatio),
                FeatureKey.atr(14), new BigDecimal(atr)));
    }
}
