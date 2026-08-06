package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

public final class VolatilityCompressionBreakoutStrategy implements Strategy {

    public record Config(
            int compressionLookback,
            int rangePeriod,
            BigDecimal maximumBandwidthPercentile,
            int atrPeriod,
            BigDecimal minimumAtrExpansionRatio,
            int volumePeriod,
            BigDecimal minimumVolumeRatio,
            BigDecimal stopAtrMultiplier,
            BigDecimal rewardRiskRatio,
            int maxHoldingBars
    ) {
        public Config {
            if (compressionLookback < 20 || rangePeriod <= 1
                    || maximumBandwidthPercentile.signum() < 0
                    || maximumBandwidthPercentile.compareTo(BigDecimal.valueOf(100)) > 0
                    || atrPeriod <= 0 || minimumAtrExpansionRatio.signum() <= 0
                    || volumePeriod <= 0 || minimumVolumeRatio.signum() < 0
                    || stopAtrMultiplier.signum() <= 0 || rewardRiskRatio.signum() <= 0
                    || maxHoldingBars <= 0) {
                throw new IllegalArgumentException("Invalid volatility compression breakout configuration");
            }
        }
    }

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private final Config config;
    private final FeatureKey close = FeatureKey.close();
    private final FeatureKey rangeHigh;
    private final FeatureKey rangeLow;
    private final FeatureKey compressionPercentile;
    private final FeatureKey atr;
    private final FeatureKey atrExpansion;
    private final FeatureKey volumeRatio;

    public VolatilityCompressionBreakoutStrategy(Config config) {
        this.config = config;
        this.rangeHigh = FeatureKey.rollingHigh(config.rangePeriod());
        this.rangeLow = FeatureKey.rollingLow(config.rangePeriod());
        this.compressionPercentile = FeatureKey.priorBollingerBandwidthPercentile(
                config.rangePeriod(), config.compressionLookback());
        this.atr = FeatureKey.atr(config.atrPeriod());
        this.atrExpansion = FeatureKey.atrExpansion(config.atrPeriod());
        this.volumeRatio = FeatureKey.volumeRatio(config.volumePeriod());
    }

    @Override
    public String name() {
        return "volatility-compression-breakout";
    }

    @Override
    public Set<FeatureKey> requiredFeatures() {
        return Set.of(close, rangeHigh, rangeLow, compressionPercentile,
                atr, atrExpansion, volumeRatio);
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
                    && currentClose.compareTo(current.require(rangeLow)) < 0) {
                return new StrategyDecision.Exit("opposite range break");
            }
            if (position.side() == Side.SHORT
                    && currentClose.compareTo(current.require(rangeHigh)) > 0) {
                return new StrategyDecision.Exit("opposite range break");
            }
            return StrategyDecision.hold();
        }

        if (current.require(compressionPercentile)
                        .compareTo(config.maximumBandwidthPercentile()) > 0
                || current.require(atrExpansion)
                        .compareTo(config.minimumAtrExpansionRatio()) < 0
                || current.require(volumeRatio).compareTo(config.minimumVolumeRatio()) < 0) {
            return StrategyDecision.hold();
        }

        BigDecimal atrStop = current.require(atr).multiply(config.stopAtrMultiplier(), MC);
        if (currentClose.compareTo(current.require(rangeHigh)) > 0) {
            BigDecimal rangeStop = currentClose.subtract(current.require(rangeLow), MC);
            BigDecimal stopDistance = atrStop.min(rangeStop);
            return enter(Side.LONG, stopDistance);
        }
        if (currentClose.compareTo(current.require(rangeLow)) < 0) {
            BigDecimal rangeStop = current.require(rangeHigh).subtract(currentClose, MC);
            BigDecimal stopDistance = atrStop.min(rangeStop);
            return enter(Side.SHORT, stopDistance);
        }
        return StrategyDecision.hold();
    }

    private StrategyDecision enter(Side side, BigDecimal stopDistance) {
        if (stopDistance.signum() <= 0) {
            return StrategyDecision.hold();
        }
        return new StrategyDecision.Enter(side, stopDistance,
                stopDistance.multiply(config.rewardRiskRatio(), MC));
    }
}
