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

class CrossSectionalLongPullbackStrategyTest {
    private final CrossSectionalLongPullbackStrategy strategy = new CrossSectionalLongPullbackStrategy(
            new CrossSectionalLongPullbackStrategy.Config(3, 20, 50, 14, 14, 20,
                    new BigDecimal("45"), new BigDecimal("65"), BigDecimal.ONE,
                    new BigDecimal("1.5"), BigDecimal.TWO, 32));

    @Test
    void entersOnlyAfterPullbackReclaimsFastEmaInLeadingHealthyMarket() {
        var decision = strategy.evaluate(List.of(snapshot("99", "100", 1, true),
                snapshot("101", "100", 2, true)), 1, PositionView.flat());

        StrategyDecision.Enter entry = assertInstanceOf(StrategyDecision.Enter.class, decision);
        assertEquals(Side.LONG, entry.side());
    }

    @Test
    void rejectsAssetOutsideTopRankEvenWhenItsPullbackReclaims() {
        var decision = strategy.evaluate(List.of(snapshot("99", "100", 1, true),
                snapshot("101", "100", 4, true)), 1, PositionView.flat());

        assertEquals(StrategyDecision.hold(), decision);
    }

    private static FeatureSnapshot snapshot(String close, String fast, int rank, boolean healthy) {
        Instant time = Instant.parse("2024-01-01T00:00:00Z");
        return new FeatureSnapshot(time, time, time, Map.of(
                FeatureKey.close(), new BigDecimal(close), FeatureKey.ema(20), new BigDecimal(fast),
                FeatureKey.ema(50), new BigDecimal("98"), FeatureKey.rsi(14), new BigDecimal("55"),
                FeatureKey.atr(14), BigDecimal.TWO, FeatureKey.volumeRatio(20), new BigDecimal("1.2"),
                FeatureKey.crossSectionRank(), BigDecimal.valueOf(rank),
                FeatureKey.btcMarketHealthy(), healthy ? BigDecimal.ONE : BigDecimal.ZERO));
    }
}
