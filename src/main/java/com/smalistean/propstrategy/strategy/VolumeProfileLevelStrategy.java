package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

public final class VolumeProfileLevelStrategy implements VolumeProfileAwareStrategy {
    public enum Reaction { BREAKOUT, FALSE_BREAKOUT, CHANNEL }

    public record Config(Reaction reaction, int profileLookbackBuckets, int atrPeriod,
                         int minimumPocStabilityBuckets, BigDecimal minimumZoneShare,
                         BigDecimal breakoutAtr, BigDecimal touchAtr,
                         BigDecimal stopBufferAtr, BigDecimal minimumRewardRisk,
                         int maximumHoldingBars) {
        public Config {
            if (reaction == null || profileLookbackBuckets < 2 || atrPeriod < 2
                    || minimumPocStabilityBuckets < 1 || minimumZoneShare.signum() < 0
                    || minimumZoneShare.compareTo(BigDecimal.ONE) > 0
                    || breakoutAtr.signum() < 0 || touchAtr.signum() < 0
                    || stopBufferAtr.signum() <= 0 || minimumRewardRisk.compareTo(BigDecimal.ONE) < 0
                    || maximumHoldingBars <= 0) {
                throw new IllegalArgumentException("Invalid volume-profile strategy configuration");
            }
        }
    }

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private final Config config;
    private final FeatureKey close = FeatureKey.close(), high = FeatureKey.high(), low = FeatureKey.low();
    private final FeatureKey atr, zoneLow, zoneHigh, share, stability;

    public VolumeProfileLevelStrategy(Config config) {
        this.config = config;
        atr = FeatureKey.atr(config.atrPeriod());
        zoneLow = FeatureKey.volumeProfileZoneLow(config.profileLookbackBuckets());
        zoneHigh = FeatureKey.volumeProfileZoneHigh(config.profileLookbackBuckets());
        share = FeatureKey.volumeProfileZoneShare(config.profileLookbackBuckets());
        stability = FeatureKey.volumeProfilePocStability(config.profileLookbackBuckets());
    }

    public int profileLookbackBuckets() { return config.profileLookbackBuckets(); }
    public int atrPeriod() { return config.atrPeriod(); }

    @Override public String name() {
        return "volume-profile-" + config.reaction().name().toLowerCase().replace('_', '-');
    }

    @Override public Set<FeatureKey> requiredFeatures() {
        return Set.of(close, high, low, atr, zoneLow, zoneHigh, share, stability);
    }

    @Override
    public StrategyDecision evaluate(List<FeatureSnapshot> history, int index, PositionView position) {
        if (position.isOpen()) {
            return position.barsHeld() >= config.maximumHoldingBars()
                    ? new StrategyDecision.Exit("volume-profile holding period expired")
                    : StrategyDecision.hold();
        }
        if (index < 1) return StrategyDecision.hold();
        FeatureSnapshot current = history.get(index), previous = history.get(index - 1);
        if (current.require(share).compareTo(config.minimumZoneShare()) < 0
                || current.require(stability).compareTo(
                BigDecimal.valueOf(config.minimumPocStabilityBuckets())) < 0) {
            return StrategyDecision.hold();
        }
        BigDecimal a = current.require(atr);
        if (a.signum() <= 0) return StrategyDecision.hold();
        return switch (config.reaction()) {
            case BREAKOUT -> breakout(current, previous, a);
            case FALSE_BREAKOUT -> falseBreakout(current, previous, a);
            case CHANNEL -> channel(current, previous, a);
        };
    }

    private StrategyDecision breakout(FeatureSnapshot c, FeatureSnapshot p, BigDecimal a) {
        BigDecimal lower = c.require(zoneLow), upper = c.require(zoneHigh);
        BigDecimal threshold = a.multiply(config.breakoutAtr(), MC);
        BigDecimal buffer = a.multiply(config.stopBufferAtr(), MC);
        if (p.require(close).compareTo(upper) <= 0
                && c.require(close).compareTo(upper.add(threshold, MC)) > 0) {
            BigDecimal stop = lower.subtract(buffer, MC);
            return riskMultipleEntry(Side.LONG, c.require(close), stop);
        }
        if (p.require(close).compareTo(lower) >= 0
                && c.require(close).compareTo(lower.subtract(threshold, MC)) < 0) {
            BigDecimal stop = upper.add(buffer, MC);
            return riskMultipleEntry(Side.SHORT, c.require(close), stop);
        }
        return StrategyDecision.hold();
    }

    private StrategyDecision falseBreakout(FeatureSnapshot c, FeatureSnapshot p, BigDecimal a) {
        BigDecimal lower = c.require(zoneLow), upper = c.require(zoneHigh);
        BigDecimal threshold = a.multiply(config.breakoutAtr(), MC);
        BigDecimal buffer = a.multiply(config.stopBufferAtr(), MC);
        if (p.require(low).compareTo(lower.subtract(threshold, MC)) < 0
                && p.require(close).compareTo(lower) < 0 && c.require(close).compareTo(lower) > 0
                && c.require(close).compareTo(upper) < 0) {
            BigDecimal stop = p.require(low).min(c.require(low)).subtract(buffer, MC);
            return structuralEntry(Side.LONG, c.require(close), stop, upper);
        }
        if (p.require(high).compareTo(upper.add(threshold, MC)) > 0
                && p.require(close).compareTo(upper) > 0 && c.require(close).compareTo(upper) < 0
                && c.require(close).compareTo(lower) > 0) {
            BigDecimal stop = p.require(high).max(c.require(high)).add(buffer, MC);
            return structuralEntry(Side.SHORT, c.require(close), stop, lower);
        }
        return StrategyDecision.hold();
    }

    private StrategyDecision channel(FeatureSnapshot c, FeatureSnapshot p, BigDecimal a) {
        BigDecimal lower = c.require(zoneLow), upper = c.require(zoneHigh);
        BigDecimal touch = a.multiply(config.touchAtr(), MC);
        BigDecimal buffer = a.multiply(config.stopBufferAtr(), MC);
        if (p.require(low).compareTo(lower.add(touch, MC)) <= 0
                && c.require(close).compareTo(lower) > 0
                && c.require(close).compareTo(p.require(close)) > 0
                && c.require(close).compareTo(upper) < 0) {
            return structuralEntry(Side.LONG, c.require(close), lower.subtract(buffer, MC), upper);
        }
        if (p.require(high).compareTo(upper.subtract(touch, MC)) >= 0
                && c.require(close).compareTo(upper) < 0
                && c.require(close).compareTo(p.require(close)) < 0
                && c.require(close).compareTo(lower) > 0) {
            return structuralEntry(Side.SHORT, c.require(close), upper.add(buffer, MC), lower);
        }
        return StrategyDecision.hold();
    }

    private StrategyDecision riskMultipleEntry(Side side, BigDecimal entry, BigDecimal stop) {
        BigDecimal risk = risk(side, entry, stop);
        if (risk.signum() <= 0) return StrategyDecision.hold();
        BigDecimal reward = risk.multiply(config.minimumRewardRisk(), MC);
        BigDecimal target = side == Side.LONG ? entry.add(reward, MC) : entry.subtract(reward, MC);
        return target.signum() > 0 ? new StrategyDecision.EnterAtLevels(side, stop, target)
                : StrategyDecision.hold();
    }

    private StrategyDecision structuralEntry(Side side, BigDecimal entry,
                                             BigDecimal stop, BigDecimal target) {
        BigDecimal risk = risk(side, entry, stop);
        BigDecimal reward = side == Side.LONG ? target.subtract(entry, MC) : entry.subtract(target, MC);
        if (risk.signum() <= 0 || reward.compareTo(risk.multiply(config.minimumRewardRisk(), MC)) < 0) {
            return StrategyDecision.hold();
        }
        return new StrategyDecision.EnterAtLevels(side, stop, target);
    }

    private static BigDecimal risk(Side side, BigDecimal entry, BigDecimal stop) {
        return side == Side.LONG ? entry.subtract(stop, MC) : stop.subtract(entry, MC);
    }
}
