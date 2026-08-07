package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class VolumeProfileLevelStrategyTest {
    @Test
    void breakoutClosesBeyondStableZone() {
        var strategy = strategy(VolumeProfileLevelStrategy.Reaction.BREAKOUT);
        var decision = strategy.evaluate(List.of(
                snapshot(0, "109", "110", "108", "100", "110"),
                snapshot(1, "112", "113", "109", "100", "110")), 1, PositionView.flat());
        assertInstanceOf(StrategyDecision.EnterAtLevels.class, decision);
    }

    @Test
    void falseBreakoutMustReclaimAndHaveThreeToOneRoom() {
        var strategy = strategy(VolumeProfileLevelStrategy.Reaction.FALSE_BREAKOUT);
        var decision = strategy.evaluate(List.of(
                snapshot(0, "98", "99", "97", "100", "120"),
                snapshot(1, "102", "104", "99", "100", "120")), 1, PositionView.flat());
        assertInstanceOf(StrategyDecision.EnterAtLevels.class, decision);
    }

    @Test
    void channelRejectsLowerBoundaryTowardUpperBoundary() {
        var strategy = strategy(VolumeProfileLevelStrategy.Reaction.CHANNEL);
        var decision = strategy.evaluate(List.of(
                snapshot(0, "101", "103", "100", "100", "120"),
                snapshot(1, "103", "104", "101", "100", "120")), 1, PositionView.flat());
        assertInstanceOf(StrategyDecision.EnterAtLevels.class, decision);
    }

    private static VolumeProfileLevelStrategy strategy(VolumeProfileLevelStrategy.Reaction reaction) {
        return new VolumeProfileLevelStrategy(new VolumeProfileLevelStrategy.Config(
                reaction, 288, 14, 8, new BigDecimal("0.02"), new BigDecimal("0.10"),
                new BigDecimal("0.15"), new BigDecimal("0.15"), new BigDecimal("3"), 96));
    }

    private static FeatureSnapshot snapshot(long minute, String close, String high, String low,
                                            String zoneLow, String zoneHigh) {
        Instant time = Instant.parse("2024-01-01T00:00:00Z").plusSeconds(minute * 900);
        return new FeatureSnapshot(time, time.plusSeconds(899), time.plusSeconds(900), Map.of(
                FeatureKey.close(), new BigDecimal(close), FeatureKey.high(), new BigDecimal(high),
                FeatureKey.low(), new BigDecimal(low), FeatureKey.atr(14), BigDecimal.ONE,
                FeatureKey.volumeProfileZoneLow(288), new BigDecimal(zoneLow),
                FeatureKey.volumeProfileZoneHigh(288), new BigDecimal(zoneHigh),
                FeatureKey.volumeProfileZoneShare(288), new BigDecimal("0.10"),
                FeatureKey.volumeProfilePocStability(288), new BigDecimal("20")));
    }
}
