package com.smalistean.propstrategy.strategy.gerchik;

import com.smalistean.propstrategy.strategy.PositionView;
import com.smalistean.propstrategy.strategy.Side;
import com.smalistean.propstrategy.strategy.Strategy;
import com.smalistean.propstrategy.strategy.StrategyDecision;
import com.smalistean.propstrategy.strategy.StrategyFactory;
import com.smalistean.propstrategy.strategy.StrategyParameters;
import com.smalistean.propstrategy.strategy.VolumeProfileAwareStrategy;

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

class GerchikLevelStrategyTest {

    @Test
    void entersAfterFalseBreakoutReclaimsConfirmedSupport() {
        GerchikLevelStrategy strategy = strategy(GerchikLevelStrategy.Reaction.FALSE_BREAKOUT);
        List<FeatureSnapshot> history = baseHistory();
        history.set(24, snapshot(24, "89.8", "91", "89.4"));
        history.set(25, snapshot(25, "90.6", "91.2", "89.8"));

        StrategyDecision.EnterAtLevels entry = assertInstanceOf(
                StrategyDecision.EnterAtLevels.class,
                strategy.evaluate(history, 25, PositionView.flat()));

        assertEquals(Side.LONG, entry.side());
        assertEquals(0, entry.stopPrice().compareTo(decimal("89.2")));
        assertEquals(0, entry.targetPrice().compareTo(decimal("94.8")));
    }

    @Test
    void doesNotTradeAnUnconfirmedLevel() {
        GerchikLevelStrategy strategy = strategy(GerchikLevelStrategy.Reaction.FALSE_BREAKOUT);
        List<FeatureSnapshot> history = baseHistory();
        history.set(15, snapshot(15, "95", "96", "94"));
        history.set(24, snapshot(24, "89.8", "91", "89.4"));
        history.set(25, snapshot(25, "90.6", "91.2", "89.8"));

        assertEquals(StrategyDecision.hold(), strategy.evaluate(history, 25, PositionView.flat()));
    }

    private static GerchikLevelStrategy strategy(GerchikLevelStrategy.Reaction reaction) {
        return new GerchikLevelStrategy(new GerchikLevelStrategy.Config(
                reaction, 20, 14, 1, 3, decimal("0.2"), decimal("0.35"),
                3, decimal("1"), decimal("0.1"), decimal("0.2"),
                decimal("3"), decimal("3"), 20));
    }

    private static List<FeatureSnapshot> baseHistory() {
        List<FeatureSnapshot> result = new ArrayList<>();
        for (int i = 0; i <= 25; i++) result.add(snapshot(i, "95", "96", "94"));
        result.set(9, snapshot(9, "92", "93", "91"));
        result.set(10, snapshot(10, "91", "92", "90"));
        result.set(11, snapshot(11, "92", "93", "91"));
        result.set(14, snapshot(14, "92", "93", "91"));
        result.set(15, snapshot(15, "91", "92", "90.1"));
        result.set(16, snapshot(16, "92", "93", "91"));
        result.set(17, snapshot(17, "92", "93", "91"));
        result.set(18, snapshot(18, "91", "92", "90.05"));
        result.set(19, snapshot(19, "92", "93", "91"));
        return result;
    }

    private static FeatureSnapshot snapshot(int index, String close, String high, String low) {
        Instant open = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(index * 900L);
        return new FeatureSnapshot(open, open.plusSeconds(899), open.plusSeconds(900), Map.of(
                FeatureKey.close(), decimal(close), FeatureKey.high(), decimal(high),
                FeatureKey.low(), decimal(low), FeatureKey.atr(14), decimal("1")));
    }

    private static BigDecimal decimal(String value) { return new BigDecimal(value); }
}
