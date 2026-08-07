package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;
import com.smalistean.propstrategy.feature.MultiTimeframeFeatureAssembler;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class MultiTimeframeFlatLongStrategyTest {

    private final MultiTimeframeFlatLongStrategy strategy = new MultiTimeframeFlatLongStrategy(
            new MultiTimeframeFlatLongStrategy.Config(decimal("2"), decimal("30"), decimal("50"),
                    decimal("1.05"), decimal("1.5"), decimal("2"), 3, 96));

    @Test
    void entersAfterFlatRegimeFifteenMinuteSetupAndRisingFiveMinuteClose() {
        StrategyDecision.Enter entry = assertInstanceOf(StrategyDecision.Enter.class,
                strategy.evaluate(List.of(snapshot(0, "100"), snapshot(1, "101")),
                        1, PositionView.flat()));

        assertEquals(Side.LONG, entry.side());
        assertEquals(0, entry.stopDistance().compareTo(decimal("6")));
        assertEquals(0, entry.targetDistance().compareTo(decimal("12")));
    }

    @Test
    void rejectsSetupWithoutFiveMinuteUpwardConfirmation() {
        assertEquals(StrategyDecision.hold(), strategy.evaluate(
                List.of(snapshot(0, "100"), snapshot(1, "99")), 1, PositionView.flat()));
    }

    private static FeatureSnapshot snapshot(int index, String fiveMinuteClose) {
        Instant time = Instant.EPOCH.plusSeconds(index * 300L);
        return new FeatureSnapshot(time, time.plusSeconds(299), time.plusSeconds(300), Map.of(
                FeatureKey.close(), decimal(fiveMinuteClose),
                MultiTimeframeFeatureAssembler.CLOSE_15M, decimal("105"),
                MultiTimeframeFeatureAssembler.EMA_15M, decimal("100"),
                MultiTimeframeFeatureAssembler.RSI_15M, decimal("29"),
                MultiTimeframeFeatureAssembler.PREVIOUS_RSI_15M, decimal("31"),
                MultiTimeframeFeatureAssembler.ATR_15M, decimal("4"),
                MultiTimeframeFeatureAssembler.ATR_EXPANSION_15M, decimal("1.01"),
                MultiTimeframeFeatureAssembler.AGE_5M_BARS, BigDecimal.ZERO,
                MultiTimeframeFeatureAssembler.MOVE_24H_PERCENT, BigDecimal.ONE));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
