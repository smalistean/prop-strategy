package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

public final class StructuralChannelStrategy implements Strategy {

    public record Config(int channelPeriod, int atrPeriod, int minimumTouches,
                         BigDecimal touchToleranceAtr, BigDecimal entryZoneAtr,
                         BigDecimal stopBufferAtr, BigDecimal targetBufferAtr,
                         BigDecimal minimumChannelRiskMultiple,
                         BigDecimal minimumRewardRisk, int maximumHoldingBars) {
        public Config {
            if (channelPeriod < 10 || atrPeriod <= 1 || minimumTouches < 2
                    || touchToleranceAtr.signum() < 0 || entryZoneAtr.signum() <= 0
                    || stopBufferAtr.signum() <= 0 || targetBufferAtr.signum() < 0
                    || minimumChannelRiskMultiple.compareTo(BigDecimal.ONE) < 0
                    || minimumRewardRisk.compareTo(BigDecimal.ONE) < 0
                    || maximumHoldingBars <= 0) {
                throw new IllegalArgumentException("Invalid structural channel configuration");
            }
        }
    }

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private final Config config;
    private final FeatureKey close = FeatureKey.close();
    private final FeatureKey high = FeatureKey.high();
    private final FeatureKey low = FeatureKey.low();
    private final FeatureKey channelHigh;
    private final FeatureKey channelLow;
    private final FeatureKey atr;

    public StructuralChannelStrategy(Config config) {
        this.config = config;
        channelHigh = FeatureKey.rollingHigh(config.channelPeriod());
        channelLow = FeatureKey.rollingLow(config.channelPeriod());
        atr = FeatureKey.atr(config.atrPeriod());
    }

    @Override
    public String name() {
        return "structural-channel";
    }

    @Override
    public Set<FeatureKey> requiredFeatures() {
        return Set.of(close, high, low, channelHigh, channelLow, atr);
    }

    @Override
    public StrategyDecision evaluate(List<FeatureSnapshot> history, int index,
                                     PositionView position) {
        if (position.isOpen()) {
            return position.barsHeld() >= config.maximumHoldingBars()
                    ? new StrategyDecision.Exit("channel holding period expired")
                    : StrategyDecision.hold();
        }
        if (index < config.channelPeriod()) {
            return StrategyDecision.hold();
        }

        FeatureSnapshot current = history.get(index);
        BigDecimal price = current.require(close);
        BigDecimal upper = current.require(channelHigh);
        BigDecimal lower = current.require(channelLow);
        BigDecimal currentAtr = current.require(atr);
        BigDecimal width = upper.subtract(lower, MC);
        if (width.signum() <= 0 || currentAtr.signum() <= 0) {
            return StrategyDecision.hold();
        }

        BigDecimal tolerance = currentAtr.multiply(config.touchToleranceAtr(), MC);
        int supportTouches = 0;
        int resistanceTouches = 0;
        for (int i = index - config.channelPeriod(); i < index; i++) {
            FeatureSnapshot prior = history.get(i);
            if (prior.require(low).compareTo(lower.add(tolerance, MC)) <= 0) {
                supportTouches++;
            }
            if (prior.require(high).compareTo(upper.subtract(tolerance, MC)) >= 0) {
                resistanceTouches++;
            }
        }
        if (supportTouches < config.minimumTouches()
                || resistanceTouches < config.minimumTouches()) {
            return StrategyDecision.hold();
        }

        BigDecimal entryZone = currentAtr.multiply(config.entryZoneAtr(), MC);
        BigDecimal stopBuffer = currentAtr.multiply(config.stopBufferAtr(), MC);
        BigDecimal targetBuffer = currentAtr.multiply(config.targetBufferAtr(), MC);
        if (price.compareTo(lower.add(entryZone, MC)) <= 0 && price.compareTo(lower) >= 0) {
            BigDecimal stopDistance = price.subtract(lower.subtract(stopBuffer, MC), MC);
            BigDecimal targetDistance = upper.subtract(targetBuffer, MC).subtract(price, MC);
            if (valid(width, stopDistance, targetDistance)) {
                return new StrategyDecision.EnterAtLevels(
                        Side.LONG, lower.subtract(stopBuffer, MC),
                        upper.subtract(targetBuffer, MC));
            }
        }
        if (price.compareTo(upper.subtract(entryZone, MC)) >= 0 && price.compareTo(upper) <= 0) {
            BigDecimal stopDistance = upper.add(stopBuffer, MC).subtract(price, MC);
            BigDecimal targetDistance = price.subtract(lower.add(targetBuffer, MC), MC);
            if (valid(width, stopDistance, targetDistance)) {
                return new StrategyDecision.EnterAtLevels(
                        Side.SHORT, upper.add(stopBuffer, MC),
                        lower.add(targetBuffer, MC));
            }
        }
        return StrategyDecision.hold();
    }

    private boolean valid(BigDecimal width, BigDecimal risk, BigDecimal reward) {
        return risk.signum() > 0 && reward.signum() > 0
                && width.compareTo(risk.multiply(config.minimumChannelRiskMultiple(), MC)) >= 0
                && reward.compareTo(risk.multiply(config.minimumRewardRisk(), MC)) >= 0;
    }
}
