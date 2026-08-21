package com.smalistean.propstrategy.strategy.apollo;

import com.smalistean.propstrategy.strategy.PositionView;
import com.smalistean.propstrategy.strategy.Side;
import com.smalistean.propstrategy.strategy.Strategy;
import com.smalistean.propstrategy.strategy.StrategyDecision;
import com.smalistean.propstrategy.strategy.StrategyFactory;
import com.smalistean.propstrategy.strategy.StrategyParameters;
import com.smalistean.propstrategy.strategy.VolumeProfileAwareStrategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ApolloV5LiquidityLimitStrategyTest {

    private static ApolloV5LiquidityLimitStrategy strategy() {
        return new ApolloV5LiquidityLimitStrategy(new ApolloV5LiquidityLimitStrategy.Config(
                14, 20, 12, 48, bd("0.75"), bd("2.5"), bd("0.35"), bd("0.025"), bd("0.35"),
                bd("0.1"), bd("0.25"), 24, 12, bd("0.15"),
                bd("1.2"), bd("0.02"), bd("0.05"), bd("1.2"),
                bd("0.25"), bd("3"), 96, 0, 999, bd("0.1"), bd("0"), 0, 3, 0, bd("0"), bd("0"), 2, 0, 0, 0, 96));
    }

    @Test
    void entersOnFirstRevisitReclaimWithoutWaitingForSwingReversal() {
        List<FeatureSnapshot> history = new ArrayList<>();
        history.add(snapshot(0, "111", "112", "110", "111")); // accepted breakout
        history.add(snapshot(1, "106", "108", "105", "106")); // acceptance close
        history.add(snapshot(2, "100", "104", "98", "103"));  // POC sweep and reclaim: first revisit

        assertInstanceOf(StrategyDecision.EnterAtLevels.class,
                strategy().evaluate(history, 2, PositionView.flat()));
    }

    @Test
    void holdsWithoutReclaimOnTheRevisitBar() {
        List<FeatureSnapshot> history = new ArrayList<>();
        history.add(snapshot(0, "111", "112", "110", "111"));
        history.add(snapshot(1, "106", "108", "105", "106"));
        // Revisit bar but close does not reclaim above the zone high (102).
        history.add(snapshotAt(2, "100", "104", "98", "101", true));

        assertInstanceOf(StrategyDecision.Hold.class, strategy().evaluate(history, 2, PositionView.flat()));
    }

    private static FeatureSnapshot snapshot(int index, String open, String high, String low, String close) {
        return snapshotAt(index, open, high, low, close, index == 2);
    }

    private static FeatureSnapshot snapshotAt(int index, String open, String high, String low, String close,
                                               boolean firstRevisit) {
        Instant time = Instant.parse("2024-01-01T00:00:00Z").plusSeconds(index * 900L);
        Map<FeatureKey, BigDecimal> values = new HashMap<>();
        values.put(FeatureKey.open(), bd(open));
        values.put(FeatureKey.high(), bd(high));
        values.put(FeatureKey.low(), bd(low));
        values.put(FeatureKey.close(), bd(close));
        values.put(FeatureKey.atr(14), bd("2"));
        values.put(FeatureKey.volumeRatio(20), bd("1.5"));
        values.put(FeatureKey.selectedBaseBars(), bd("12"));
        values.put(FeatureKey.selectedBaseLow(), bd("100"));
        values.put(FeatureKey.selectedBaseHigh(), bd("105"));
        values.put(FeatureKey.selectedBaseZoneLow(), bd("100"));
        values.put(FeatureKey.selectedBaseZoneHigh(), bd("102"));
        values.put(FeatureKey.selectedBaseZoneShare(), bd("0.1"));
        values.put(FeatureKey.selectedBasePocShare(), bd("0.1"));
        values.put(FeatureKey.selectedBaseTotalQuote(), bd("1000"));
        values.put(FeatureKey.selectedBaseVolumeRatio(), bd("1.5"));
        values.put(FeatureKey.selectedBaseId(), bd("1"));
        values.put(FeatureKey.selectedBaseBreakoutSide(), bd("1"));
        values.put(FeatureKey.selectedBaseBreakoutVolumeRatio(), bd("1.5"));
        values.put(FeatureKey.selectedBaseFirstRevisit(), firstRevisit ? bd("1") : bd("0"));
        values.put(FeatureKey.selectedBaseTarget(), bd("150"));
        return new FeatureSnapshot(time, time, time, values);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
