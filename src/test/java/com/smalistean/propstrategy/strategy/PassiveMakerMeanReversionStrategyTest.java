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

class PassiveMakerMeanReversionStrategyTest {

    private final PassiveMakerMeanReversionStrategy strategy =
            new PassiveMakerMeanReversionStrategy(new PassiveMakerMeanReversionStrategy.Config(
                    60, 15, decimal("12"), 7, decimal("45"), decimal("55"), 14,
                    decimal("0.15"), decimal("2"), decimal("0.75"), 30));

    @Test
    void quotesLongBelowFlatMean() {
        List<FeatureSnapshot> history = history();
        history.set(15, snapshot(15, "99", "100", "40", "2"));

        StrategyDecision.Enter entry = assertInstanceOf(StrategyDecision.Enter.class,
                strategy.evaluate(history, 15, PositionView.flat()));

        assertEquals(Side.LONG, entry.side());
        assertEquals(0, entry.stopDistance().compareTo(decimal("4")));
        assertEquals(0, entry.targetDistance().compareTo(decimal("1.5")));
    }

    @Test
    void rejectsEntryWhenMeanIsTrending() {
        List<FeatureSnapshot> history = history();
        history.set(15, snapshot(15, "100", "101", "40", "2"));

        assertEquals(StrategyDecision.hold(), strategy.evaluate(history, 15, PositionView.flat()));
    }

    private static List<FeatureSnapshot> history() {
        List<FeatureSnapshot> result = new ArrayList<>();
        for (int index = 0; index <= 15; index++) {
            result.add(snapshot(index, "100", "100", "50", "2"));
        }
        return result;
    }

    private static FeatureSnapshot snapshot(int index, String close, String ema,
                                            String rsi, String atr) {
        Instant open = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(index * 60L);
        Instant closeTime = open.plusSeconds(60).minusMillis(1);
        return new FeatureSnapshot(open, closeTime, closeTime.plusMillis(1), Map.of(
                FeatureKey.close(), decimal(close), FeatureKey.ema(60), decimal(ema),
                FeatureKey.rsi(7), decimal(rsi), FeatureKey.atr(14), decimal(atr)));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
