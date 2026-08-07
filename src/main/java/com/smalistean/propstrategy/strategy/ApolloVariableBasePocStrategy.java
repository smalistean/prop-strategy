package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;
import com.smalistean.propstrategy.feature.VariableBaseDetector;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ApolloVariableBasePocStrategy implements VolumeProfileAwareStrategy {
    public record Config(int atrPeriod, int volumePeriod, int minimumBaseBars, int maximumBaseBars,
                         BigDecimal minimumBaseRangeAtr, BigDecimal maximumBaseRangeAtr,
                         BigDecimal maximumCenterDriftAtr, BigDecimal maximumSlopeAtrPerBar,
                         BigDecimal maximumPenetrationFraction, BigDecimal boundaryPenetrationAtr,
                         BigDecimal entranceDistanceAtr,
                         int breakoutSearchBars, BigDecimal breakoutAtr,
                         BigDecimal minimumBreakoutVolumeRatio, BigDecimal minimumZoneShare,
                         BigDecimal retestTouchAtr, BigDecimal stopZoneHeightFraction,
                         BigDecimal minimumRewardRisk, int maximumHoldingBars, boolean longOnly,
                         boolean hourlyAlignment, int hourlyEmaPeriod) {}
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private final Config config;
    private final FeatureKey atr, volumeRatio;
    private final Set<java.time.Instant> bases = new HashSet<>(), breakouts = new HashSet<>(),
            overlaps = new HashSet<>(), retests = new HashSet<>(), orders = new HashSet<>();

    public ApolloVariableBasePocStrategy(Config config) {
        this.config = config;
        atr = FeatureKey.atr(config.atrPeriod());
        volumeRatio = FeatureKey.volumeRatio(config.volumePeriod());
    }
    @Override public String name() { return "apollo-variable-base-poc-v3"; }
    @Override public int profileLookbackBuckets() { return config.maximumBaseBars(); }
    public VariableBaseDetector.Config detectorConfig() {
        return new VariableBaseDetector.Config(config.minimumBaseBars(), config.maximumBaseBars(),
                config.minimumBaseRangeAtr(), config.maximumBaseRangeAtr(),
                config.maximumCenterDriftAtr(), config.maximumSlopeAtrPerBar(),
                config.maximumPenetrationFraction(), config.boundaryPenetrationAtr(),
                config.entranceDistanceAtr());
    }
    public FeatureKey atrKey() { return atr; }
    public String diagnosticSummary() {
        return "Apollo v3 funnel: bases=%d volumeBreakouts=%d profileOverlaps=%d firstRetests=%d orders=%d"
                .formatted(bases.size(), breakouts.size(), overlaps.size(), retests.size(), orders.size());
    }
    @Override public Set<FeatureKey> requiredFeatures() {
        Set<FeatureKey> keys = new HashSet<>(Set.of(FeatureKey.open(), FeatureKey.close(),
                FeatureKey.high(), FeatureKey.low(), atr, volumeRatio));
        keys.addAll(Set.of(FeatureKey.selectedBaseBars(), FeatureKey.selectedBaseLow(),
                FeatureKey.selectedBaseHigh(), FeatureKey.selectedBaseZoneLow(),
                FeatureKey.selectedBaseZoneHigh(), FeatureKey.selectedBaseZoneShare()));
        if (config.hourlyAlignment()) keys.addAll(Set.of(FeatureKey.completedHourClose(),
                FeatureKey.completedHourEma(config.hourlyEmaPeriod())));
        return Set.copyOf(keys);
    }
    @Override public StrategyDecision evaluate(List<FeatureSnapshot> h, int index, PositionView position) {
        if (position.isOpen()) return position.barsHeld() >= config.maximumHoldingBars()
                ? new StrategyDecision.Exit("Apollo v3 holding period expired") : StrategyDecision.hold();
        int earliest = Math.max(0, index - config.breakoutSearchBars());
        for (int breakout = index - 2; breakout >= earliest; breakout--) {
            StrategyDecision longDecision = candidate(h, index, breakout, Side.LONG);
            if (!(longDecision instanceof StrategyDecision.Hold)) return longDecision;
            if (!config.longOnly()) {
                StrategyDecision shortDecision = candidate(h, index, breakout, Side.SHORT);
                if (!(shortDecision instanceof StrategyDecision.Hold)) return shortDecision;
            }
        }
        return StrategyDecision.hold();
    }
    private StrategyDecision candidate(List<FeatureSnapshot> h, int index, int breakout, Side side) {
        FeatureSnapshot b = h.get(breakout);
        if (!b.values().containsKey(FeatureKey.selectedBaseBars()))
            return StrategyDecision.hold();
        bases.add(b.candleOpenTime());
        if (b.require(volumeRatio).compareTo(config.minimumBreakoutVolumeRatio()) < 0
                || b.require(FeatureKey.selectedBaseZoneShare()).compareTo(config.minimumZoneShare()) < 0)
            return StrategyDecision.hold();
        BigDecimal low = b.require(FeatureKey.selectedBaseLow()), high = b.require(FeatureKey.selectedBaseHigh());
        BigDecimal threshold = b.require(atr).multiply(config.breakoutAtr(), MC);
        FeatureSnapshot confirmation = h.get(breakout + 1);
        boolean clean = side == Side.LONG ? b.require(FeatureKey.close()).compareTo(high.add(threshold)) > 0
                && confirmation.require(FeatureKey.close()).compareTo(high) > 0
                : b.require(FeatureKey.close()).compareTo(low.subtract(threshold)) < 0
                && confirmation.require(FeatureKey.close()).compareTo(low) < 0;
        if (!clean) return StrategyDecision.hold();
        if (config.hourlyAlignment()) {
            int alignment = b.require(FeatureKey.completedHourClose())
                    .compareTo(b.require(FeatureKey.completedHourEma(config.hourlyEmaPeriod())));
            if ((side == Side.LONG && alignment <= 0) || (side == Side.SHORT && alignment >= 0))
                return StrategyDecision.hold();
        }
        breakouts.add(b.candleOpenTime());
        BigDecimal zLow = b.require(FeatureKey.selectedBaseZoneLow());
        BigDecimal zHigh = b.require(FeatureKey.selectedBaseZoneHigh());
        if (zHigh.compareTo(low) < 0 || zLow.compareTo(high) > 0) return StrategyDecision.hold();
        overlaps.add(b.candleOpenTime());
        BigDecimal touch = h.get(index).require(atr).multiply(config.retestTouchAtr(), MC);
        for (int i = breakout + 2; i < index; i++) {
            if (side == Side.LONG && h.get(i).require(FeatureKey.low()).compareTo(zHigh.add(touch)) <= 0)
                return StrategyDecision.hold();
            if (side == Side.SHORT && h.get(i).require(FeatureKey.high()).compareTo(zLow.subtract(touch)) >= 0)
                return StrategyDecision.hold();
        }
        FeatureSnapshot current = h.get(index);
        boolean retest = side == Side.LONG ? current.require(FeatureKey.low()).compareTo(zHigh.add(touch)) <= 0
                && current.require(FeatureKey.close()).compareTo(zHigh) > 0
                : current.require(FeatureKey.high()).compareTo(zLow.subtract(touch)) >= 0
                && current.require(FeatureKey.close()).compareTo(zLow) < 0;
        if (!retest) return StrategyDecision.hold();
        retests.add(b.candleOpenTime());
        BigDecimal buffer = zHigh.subtract(zLow).multiply(config.stopZoneHeightFraction(), MC);
        BigDecimal stop = side == Side.LONG ? zLow.subtract(buffer) : zHigh.add(buffer);
        BigDecimal entry = current.require(FeatureKey.close());
        BigDecimal risk = side == Side.LONG ? entry.subtract(stop) : stop.subtract(entry);
        if (risk.signum() <= 0) return StrategyDecision.hold();
        orders.add(b.candleOpenTime());
        BigDecimal reward = risk.multiply(config.minimumRewardRisk(), MC);
        return new StrategyDecision.EnterAtLevels(side, stop,
                side == Side.LONG ? entry.add(reward) : entry.subtract(reward));
    }
}
