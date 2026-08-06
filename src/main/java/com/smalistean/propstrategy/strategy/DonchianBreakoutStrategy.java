package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

public final class DonchianBreakoutStrategy implements Strategy {

    public record Config(
            int entryLookback,
            int exitLookback,
            int volumePeriod,
            BigDecimal minimumVolumeRatio,
            int atrPeriod,
            BigDecimal stopAtrMultiplier,
            BigDecimal rewardRiskRatio,
            int maxHoldingBars
    ) {
        public Config {
            if (entryLookback <= 1 || exitLookback <= 1 || exitLookback > entryLookback
                    || volumePeriod <= 0 || minimumVolumeRatio.signum() < 0
                    || atrPeriod <= 0 || stopAtrMultiplier.signum() <= 0
                    || rewardRiskRatio.signum() <= 0 || maxHoldingBars <= 0) {
                throw new IllegalArgumentException("Invalid Donchian breakout configuration");
            }
        }
    }

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private final Config config;
    private final FeatureKey close = FeatureKey.close();
    private final FeatureKey entryHigh;
    private final FeatureKey entryLow;
    private final FeatureKey exitHigh;
    private final FeatureKey exitLow;
    private final FeatureKey volumeRatio;
    private final FeatureKey atr;

    public DonchianBreakoutStrategy(Config config) {
        this.config = config;
        this.entryHigh = FeatureKey.rollingHigh(config.entryLookback());
        this.entryLow = FeatureKey.rollingLow(config.entryLookback());
        this.exitHigh = FeatureKey.rollingHigh(config.exitLookback());
        this.exitLow = FeatureKey.rollingLow(config.exitLookback());
        this.volumeRatio = FeatureKey.volumeRatio(config.volumePeriod());
        this.atr = FeatureKey.atr(config.atrPeriod());
    }

    @Override
    public String name() {
        return "donchian-breakout";
    }

    @Override
    public Set<FeatureKey> requiredFeatures() {
        return Set.of(close, entryHigh, entryLow, exitHigh, exitLow, volumeRatio, atr);
    }

    @Override
    public StrategyDecision evaluate(List<FeatureSnapshot> history, int index,
                                     PositionView position) {
        FeatureSnapshot current = history.get(index);
        BigDecimal currentClose = current.require(close);

        if (position.isOpen()) {
            if (position.barsHeld() >= config.maxHoldingBars()) {
                return new StrategyDecision.Exit("maximum holding period");
            }
            if (position.side() == Side.LONG
                    && currentClose.compareTo(current.require(exitLow)) < 0) {
                return new StrategyDecision.Exit("Donchian channel exit");
            }
            if (position.side() == Side.SHORT
                    && currentClose.compareTo(current.require(exitHigh)) > 0) {
                return new StrategyDecision.Exit("Donchian channel exit");
            }
            return StrategyDecision.hold();
        }

        if (current.require(volumeRatio).compareTo(config.minimumVolumeRatio()) < 0) {
            return StrategyDecision.hold();
        }
        BigDecimal stopDistance = current.require(atr).multiply(config.stopAtrMultiplier(), MC);
        BigDecimal targetDistance = stopDistance.multiply(config.rewardRiskRatio(), MC);
        if (currentClose.compareTo(current.require(entryHigh)) > 0) {
            return new StrategyDecision.Enter(Side.LONG, stopDistance, targetDistance);
        }
        if (currentClose.compareTo(current.require(entryLow)) < 0) {
            return new StrategyDecision.Enter(Side.SHORT, stopDistance, targetDistance);
        }
        return StrategyDecision.hold();
    }
}
