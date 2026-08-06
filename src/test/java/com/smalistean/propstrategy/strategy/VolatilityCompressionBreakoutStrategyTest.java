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

class VolatilityCompressionBreakoutStrategyTest {

    private final VolatilityCompressionBreakoutStrategy strategy =
            new VolatilityCompressionBreakoutStrategy(
                    new VolatilityCompressionBreakoutStrategy.Config(
                            96, 20, decimal("20"), 14, decimal("1.10"),
                            20, decimal("1.10"), decimal("1.5"), decimal("2.5"), 64));

    @Test
    void entersLongWhenCompressedRangeBreaksWithAtrAndVolumeConfirmation() {
        StrategyDecision.Enter entry = assertInstanceOf(StrategyDecision.Enter.class,
                strategy.evaluate(List.of(snapshot("111", "110", "90", "10",
                        "1.12", "1.20", "4")), 0, PositionView.flat()));

        assertEquals(Side.LONG, entry.side());
        assertEquals(0, entry.stopDistance().compareTo(decimal("6")));
        assertEquals(0, entry.targetDistance().compareTo(decimal("15")));
    }

    @Test
    void holdsWhenPriorBandwidthWasNotCompressed() {
        StrategyDecision decision = strategy.evaluate(List.of(snapshot("111", "110", "90", "30",
                "1.12", "1.20", "4")), 0, PositionView.flat());

        assertEquals(StrategyDecision.hold(), decision);
    }

    private static FeatureSnapshot snapshot(String close, String high, String low,
                                            String percentile, String atrExpansion,
                                            String volumeRatio, String atr) {
        Instant closeTime = Instant.parse("2026-01-01T00:14:59.999Z");
        return new FeatureSnapshot(Instant.parse("2026-01-01T00:00:00Z"), closeTime,
                closeTime.plusMillis(1), Map.of(
                FeatureKey.close(), decimal(close),
                FeatureKey.rollingHigh(20), decimal(high),
                FeatureKey.rollingLow(20), decimal(low),
                FeatureKey.priorBollingerBandwidthPercentile(20, 96), decimal(percentile),
                FeatureKey.atr(14), decimal(atr),
                FeatureKey.atrExpansion(14), decimal(atrExpansion),
                FeatureKey.volumeRatio(20), decimal(volumeRatio)));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
