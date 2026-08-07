package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

/** Mechanical, testable interpretation of the base + fixed-volume-profile retest setup. */
public final class ApolloBasePocRetestStrategy implements VolumeProfileAwareStrategy {
    public record Config(int atrPeriod, int volumePeriod, int baseBars, int breakoutSearchBars,
                         BigDecimal minimumBaseRangeAtr, BigDecimal maximumBaseRangeAtr,
                         BigDecimal breakoutAtr, BigDecimal minimumBreakoutVolumeRatio,
                         BigDecimal minimumZoneShare,
                         BigDecimal retestTouchAtr, BigDecimal stopZoneHeightFraction,
                         BigDecimal minimumRewardRisk, int maximumHoldingBars) {
        public Config {
            if (atrPeriod < 2 || volumePeriod < 2 || baseBars < 5
                    || breakoutSearchBars < 2 || minimumBaseRangeAtr.signum() <= 0
                    || maximumBaseRangeAtr.compareTo(minimumBaseRangeAtr) <= 0
                    || breakoutAtr.signum() < 0 || minimumBreakoutVolumeRatio.signum() <= 0
                    || minimumZoneShare.signum() < 0
                    || minimumZoneShare.compareTo(BigDecimal.ONE) > 0 || retestTouchAtr.signum() < 0
                    || stopZoneHeightFraction.signum() <= 0
                    || minimumRewardRisk.compareTo(BigDecimal.ONE) < 0 || maximumHoldingBars <= 0) {
                throw new IllegalArgumentException("Invalid Apollo base/POC configuration");
            }
        }
    }

    private record Base(BigDecimal low, BigDecimal high) {}
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private final Config config;
    private final FeatureKey open = FeatureKey.open(), close = FeatureKey.close();
    private final FeatureKey high = FeatureKey.high(), low = FeatureKey.low();
    private final FeatureKey atr, volumeRatio, zoneLow, zoneHigh, zoneShare;

    public ApolloBasePocRetestStrategy(Config config) {
        this.config = config;
        atr = FeatureKey.atr(config.atrPeriod());
        volumeRatio = FeatureKey.volumeRatio(config.volumePeriod());
        zoneLow = FeatureKey.exactBaseZoneLow(config.baseBars());
        zoneHigh = FeatureKey.exactBaseZoneHigh(config.baseBars());
        zoneShare = FeatureKey.exactBaseZoneShare(config.baseBars());
    }

    @Override public String name() { return "apollo-exact-base-poc-retest"; }
    @Override public int profileLookbackBuckets() { return config.baseBars(); }
    public int baseBars() { return config.baseBars(); }

    @Override public Set<FeatureKey> requiredFeatures() {
        return Set.of(open, close, high, low, atr, volumeRatio, zoneLow, zoneHigh, zoneShare);
    }

    @Override
    public StrategyDecision evaluate(List<FeatureSnapshot> history, int index, PositionView position) {
        if (position.isOpen()) {
            return position.barsHeld() >= config.maximumHoldingBars()
                    ? new StrategyDecision.Exit("Apollo POC retest holding period expired")
                    : StrategyDecision.hold();
        }
        if (index < config.baseBars() + 2) return StrategyDecision.hold();
        int earliest = Math.max(config.baseBars(), index - config.breakoutSearchBars());
        for (int breakout = index - 2; breakout >= earliest; breakout--) {
            StrategyDecision longEntry = candidate(history, index, breakout, Side.LONG);
            if (!(longEntry instanceof StrategyDecision.Hold)) return longEntry;
            StrategyDecision shortEntry = candidate(history, index, breakout, Side.SHORT);
            if (!(shortEntry instanceof StrategyDecision.Hold)) return shortEntry;
        }
        return StrategyDecision.hold();
    }

    private StrategyDecision candidate(List<FeatureSnapshot> h, int index, int breakout, Side side) {
        BigDecimal a = h.get(breakout).require(atr);
        if (a.signum() <= 0 || h.get(breakout).require(volumeRatio)
                .compareTo(config.minimumBreakoutVolumeRatio()) < 0) return StrategyDecision.hold();
        Base base = base(h, breakout);
        BigDecimal widthAtr = base.high().subtract(base.low(), MC).divide(a, MC);
        if (widthAtr.compareTo(config.minimumBaseRangeAtr()) < 0
                || widthAtr.compareTo(config.maximumBaseRangeAtr()) > 0) return StrategyDecision.hold();
        FeatureSnapshot breakBar = h.get(breakout), confirmation = h.get(breakout + 1);
        if (breakBar.require(zoneShare).compareTo(config.minimumZoneShare()) < 0)
            return StrategyDecision.hold();
        BigDecimal threshold = a.multiply(config.breakoutAtr(), MC);
        boolean confirmed = side == Side.LONG
                ? breakBar.require(close).compareTo(base.high().add(threshold, MC)) > 0
                    && confirmation.require(close).compareTo(base.high()) > 0
                : breakBar.require(close).compareTo(base.low().subtract(threshold, MC)) < 0
                    && confirmation.require(close).compareTo(base.low()) < 0;
        if (!confirmed) return StrategyDecision.hold();

        FeatureSnapshot current = h.get(index);
        BigDecimal zLow = breakBar.require(zoneLow), zHigh = breakBar.require(zoneHigh);
        if (zHigh.compareTo(base.low()) < 0 || zLow.compareTo(base.high()) > 0) {
            return StrategyDecision.hold();
        }
        BigDecimal touch = current.require(atr).multiply(config.retestTouchAtr(), MC);
        for (int j = breakout + 2; j < index; j++) {
            if (side == Side.LONG && h.get(j).require(low).compareTo(zHigh.add(touch, MC)) <= 0)
                return StrategyDecision.hold();
            if (side == Side.SHORT && h.get(j).require(high).compareTo(zLow.subtract(touch, MC)) >= 0)
                return StrategyDecision.hold();
        }
        boolean retest = side == Side.LONG
                ? current.require(low).compareTo(zHigh.add(touch, MC)) <= 0
                    && current.require(close).compareTo(zHigh) > 0
                : current.require(high).compareTo(zLow.subtract(touch, MC)) >= 0
                    && current.require(close).compareTo(zLow) < 0;
        if (!retest) return StrategyDecision.hold();

        BigDecimal zoneHeight = zHigh.subtract(zLow, MC);
        BigDecimal buffer = zoneHeight.multiply(config.stopZoneHeightFraction(), MC);
        BigDecimal stop = side == Side.LONG ? zLow.subtract(buffer, MC) : zHigh.add(buffer, MC);
        BigDecimal entry = current.require(close);
        BigDecimal risk = side == Side.LONG ? entry.subtract(stop, MC) : stop.subtract(entry, MC);
        if (risk.signum() <= 0) return StrategyDecision.hold();
        BigDecimal reward = risk.multiply(config.minimumRewardRisk(), MC);
        BigDecimal target = side == Side.LONG ? entry.add(reward, MC) : entry.subtract(reward, MC);
        return target.signum() > 0 ? new StrategyDecision.EnterAtLevels(side, stop, target)
                : StrategyDecision.hold();
    }

    private Base base(List<FeatureSnapshot> h, int breakout) {
        BigDecimal lowBody = null, highBody = null;
        for (int i = breakout - config.baseBars(); i < breakout; i++) {
            BigDecimal o = h.get(i).require(open), c = h.get(i).require(close);
            BigDecimal bodyLow = o.min(c), bodyHigh = o.max(c);
            lowBody = lowBody == null ? bodyLow : lowBody.min(bodyLow);
            highBody = highBody == null ? bodyHigh : highBody.max(bodyHigh);
        }
        return new Base(lowBody, highBody);
    }
}
