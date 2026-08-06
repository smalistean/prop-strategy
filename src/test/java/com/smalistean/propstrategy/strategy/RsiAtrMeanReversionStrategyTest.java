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

class RsiAtrMeanReversionStrategyTest {

    private final RsiAtrMeanReversionStrategy strategy = new RsiAtrMeanReversionStrategy(
            new RsiAtrMeanReversionStrategy.Config(200, 14, decimal("30"), decimal("70"),
                    decimal("50"), decimal("50"), 14, decimal("1.05"), decimal("1.5"),
                    decimal("2"), 32));

    @Test
    void entersLongOnNewOversoldCrossInLongTermUptrend() {
        List<FeatureSnapshot> history = List.of(
                snapshot("105", "100", "31", "1.01", "4"),
                snapshot("104", "100", "29", "1.02", "4"));

        StrategyDecision.Enter entry = assertInstanceOf(StrategyDecision.Enter.class,
                strategy.evaluate(history, 1, PositionView.flat()));

        assertEquals(Side.LONG, entry.side());
        assertEquals(0, entry.stopDistance().compareTo(decimal("6")));
        assertEquals(0, entry.targetDistance().compareTo(decimal("12")));
    }

    @Test
    void rejectsEntryWhenAtrIsExpandingTooQuickly() {
        List<FeatureSnapshot> history = List.of(
                snapshot("105", "100", "31", "1.01", "4"),
                snapshot("104", "100", "29", "1.06", "4"));

        assertEquals(StrategyDecision.hold(), strategy.evaluate(history, 1, PositionView.flat()));
    }

    @Test
    void exitsLongWhenRsiRevertsToItsMean() {
        PositionView position = new PositionView(Side.LONG, Instant.EPOCH,
                decimal("100"), BigDecimal.ONE, 4);

        StrategyDecision.Exit exit = assertInstanceOf(StrategyDecision.Exit.class,
                strategy.evaluate(List.of(snapshot("106", "100", "51", "1", "4")),
                        0, position));

        assertEquals("long mean reversion or trend failure", exit.reason());
    }

    private static FeatureSnapshot snapshot(String close, String ema, String rsi,
                                            String atrExpansion, String atr) {
        Instant open = Instant.parse("2026-01-01T00:00:00Z");
        Instant closeTime = Instant.parse("2026-01-01T00:14:59.999Z");
        return new FeatureSnapshot(open, closeTime, closeTime.plusMillis(1), Map.of(
                FeatureKey.close(), decimal(close),
                FeatureKey.ema(200), decimal(ema),
                FeatureKey.rsi(14), decimal(rsi),
                FeatureKey.atr(14), decimal(atr),
                FeatureKey.atrExpansion(14), decimal(atrExpansion)));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
