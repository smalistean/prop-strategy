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

class ApolloV5BasePocContinuationStrategyTest {

    @Test
    void entersOnlyAfterReclaimHigherLowAndBreakOfInterveningHigh() {
        ApolloV5BasePocContinuationStrategy strategy = new ApolloV5BasePocContinuationStrategy(
                new ApolloV5BasePocContinuationStrategy.Config(14, 20, 12, 48,
                        bd("0.75"), bd("2.5"), bd("0.35"), bd("0.025"), bd("0.35"),
                        bd("0.1"), bd("0.25"), 24, 12, 1, bd("0.25"), bd("0.15"),
                        bd("1.2"), bd("0.02"), bd("0.05"), bd("1.2"), bd("0.15"),
                        bd("0.25"), bd("3"), 96, 0, 2, bd("0.1"), bd("0"), 0, bd("0"), 2, 0));
        List<FeatureSnapshot> history = new ArrayList<>();
        history.add(snapshot(0, "111", "112", "110", "111")); // accepted breakout
        history.add(snapshot(1, "106", "108", "105", "106")); // acceptance close
        history.add(snapshot(2, "100", "104", "98", "103")); // POC sweep and reclaim
        history.add(snapshot(3, "103", "105", "102", "104"));
        history.add(snapshot(4, "104", "110", "103", "108")); // pivot high
        history.add(snapshot(5, "108", "107", "105", "106"));
        history.add(snapshot(6, "106", "107", "104", "106")); // higher pivot low
        history.add(snapshot(7, "106", "109", "105", "108"));
        history.add(snapshot(8, "108", "112", "107", "111")); // break of pivot high
        history.add(snapshot(9, "111", "112", "109", "110")); // retest / hold

        assertInstanceOf(StrategyDecision.EnterAtLevels.class,
                strategy.evaluate(history, 9, PositionView.flat()));
    }

    private static FeatureSnapshot snapshot(int index, String open, String high, String low, String close) {
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
        values.put(FeatureKey.selectedBaseFirstRevisit(), index == 2 ? bd("1") : bd("0"));
        values.put(FeatureKey.selectedBaseTarget(), bd("150"));
        return new FeatureSnapshot(time, time, time, values);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
