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

class OrderFlowExhaustionStrategyTest {

    @Test
    void entersLongOnlyAfterFrozenExhaustionConfirmation() {
        StrategyDecision.Enter entry = assertInstanceOf(StrategyDecision.Enter.class,
                strategy("0.75").evaluate(List.of(snapshot(Map.of())), 0, PositionView.flat()));
        assertEquals(Side.LONG, entry.side());
        assertEquals(0, entry.stopDistance().compareTo(new BigDecimal("2.50")));
        assertEquals(0, entry.targetDistance().compareTo(new BigDecimal("5.000")));
    }

    @Test
    void rejectsSetupWhenQualityIsBelowConfiguredSensitivityThreshold() {
        StrategyDecision decision = strategy("0.95").evaluate(List.of(snapshot(Map.of(
                FeatureKey.orderFlowQuality(240), new BigDecimal("0.90")))),
                0, PositionView.flat());
        assertInstanceOf(StrategyDecision.Hold.class, decision);
    }

    @Test
    void exitsWhenAggressiveBuyingHasRecovered() {
        StrategyDecision decision = strategy("0.75").evaluate(List.of(snapshot(Map.of(
                        FeatureKey.orderFlowImbalance(5), new BigDecimal("0.12")))), 0,
                new PositionView(Side.LONG, Instant.EPOCH, new BigDecimal("100"),
                        BigDecimal.ONE, 3));
        StrategyDecision.Exit exit = assertInstanceOf(StrategyDecision.Exit.class, decision);
        assertEquals("aggressive buying recovered", exit.reason());
    }

    private static OrderFlowExhaustionStrategy strategy(String quality) {
        return new OrderFlowExhaustionStrategy(new OrderFlowExhaustionStrategy.Config(
                200, 14, new BigDecimal("0.99"), new BigDecimal(quality),
                new BigDecimal("0.02"), new BigDecimal("-0.08"), new BigDecimal("-0.10"),
                new BigDecimal("0.04"), new BigDecimal("0.02"), new BigDecimal("0.03"),
                new BigDecimal("0.05"), new BigDecimal("-0.005"), new BigDecimal("0.001"),
                new BigDecimal("0.10"), new BigDecimal("-0.15"), new BigDecimal("1.25"),
                new BigDecimal("2.0"), 12, 6));
    }

    private static FeatureSnapshot snapshot(Map<FeatureKey, BigDecimal> overrides) {
        Map<FeatureKey, BigDecimal> values = new java.util.HashMap<>(Map.ofEntries(
                Map.entry(FeatureKey.close(), new BigDecimal("100")),
                Map.entry(FeatureKey.ema(200), new BigDecimal("100")),
                Map.entry(FeatureKey.atr(14), new BigDecimal("2")),
                Map.entry(FeatureKey.orderFlowCoverage(240), BigDecimal.ONE),
                Map.entry(FeatureKey.orderFlowQuality(240), BigDecimal.ONE),
                Map.entry(FeatureKey.orderFlowImbalance(5), BigDecimal.ZERO),
                Map.entry(FeatureKey.orderFlowImbalance(15), new BigDecimal("-0.10")),
                Map.entry(FeatureKey.largeTradeImbalance(15), new BigDecimal("-0.20")),
                Map.entry(FeatureKey.deltaAcceleration(5, 60), new BigDecimal("0.05")),
                Map.entry(FeatureKey.sellAbsorption(15), new BigDecimal("0.05")),
                Map.entry(FeatureKey.sellExhaustion(5, 15), new BigDecimal("0.03")),
                Map.entry(FeatureKey.priceFlowDivergence(15), new BigDecimal("0.10")),
                Map.entry(FeatureKey.priceReturn(15), new BigDecimal("-0.002"))));
        values.putAll(overrides);
        Instant close = Instant.parse("2025-01-01T00:04:59.999Z");
        return new FeatureSnapshot(Instant.parse("2025-01-01T00:00:00Z"), close,
                close.plusMillis(1), values);
    }
}
