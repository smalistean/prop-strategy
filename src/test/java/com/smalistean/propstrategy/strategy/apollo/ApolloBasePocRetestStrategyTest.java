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
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ApolloBasePocRetestStrategyTest {
    @Test
    void entersOnFirstPocRetestAfterVolumeConfirmedBaseBreak() {
        ApolloBasePocRetestStrategy strategy = strategy();
        List<FeatureSnapshot> history = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            history.add(snapshot(i, "101", i % 2 == 0 ? "103" : "102", "104", "100", "1.0"));
        }
        history.add(snapshot(16, "103", "106", "107", "102", "1.5"));
        history.add(snapshot(17, "106", "105", "107", "104", "1.0"));
        history.add(snapshot(18, "104", "103", "105", "102", "1.0"));

        StrategyDecision decision = strategy.evaluate(history, 18, PositionView.flat());

        assertInstanceOf(StrategyDecision.EnterAtLevels.class, decision);
    }

    private static ApolloBasePocRetestStrategy strategy() {
        return new ApolloBasePocRetestStrategy(new ApolloBasePocRetestStrategy.Config(
                14, 20, 16, 12, new BigDecimal("0.75"), new BigDecimal("2.50"),
                new BigDecimal("0.15"), new BigDecimal("1.20"), new BigDecimal("0.02"),
                new BigDecimal("0.15"), new BigDecimal("0.25"), new BigDecimal("3"), 96));
    }

    private static FeatureSnapshot snapshot(int index, String open, String close,
                                            String high, String low, String volumeRatio) {
        Instant time = Instant.parse("2024-01-01T00:00:00Z").plusSeconds(index * 900L);
        Map<FeatureKey, BigDecimal> values = new HashMap<>();
        values.put(FeatureKey.open(), new BigDecimal(open));
        values.put(FeatureKey.close(), new BigDecimal(close));
        values.put(FeatureKey.high(), new BigDecimal(high));
        values.put(FeatureKey.low(), new BigDecimal(low));
        values.put(FeatureKey.atr(14), new BigDecimal("2"));
        values.put(FeatureKey.volumeRatio(20), new BigDecimal(volumeRatio));
        values.put(FeatureKey.exactBaseZoneLow(16), new BigDecimal("100"));
        values.put(FeatureKey.exactBaseZoneHigh(16), new BigDecimal("102"));
        values.put(FeatureKey.exactBaseZoneShare(16), new BigDecimal("0.10"));
        return new FeatureSnapshot(time, time.plusSeconds(899), time.plusSeconds(900), Map.copyOf(values));
    }
}
