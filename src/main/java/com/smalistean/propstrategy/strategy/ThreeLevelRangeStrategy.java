package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class ThreeLevelRangeStrategy implements Strategy {
    public enum EntryMode { TOUCH_WITH_SCRATCH, RECLAIM }

    public record Config(EntryMode entryMode, int levelLookback, int pivotStrength, int minimumConfirmations,
                         int atrPeriod, BigDecimal levelToleranceAtr,
                         BigDecimal entryToleranceAtr, BigDecimal minimumChannelWidthAtr,
                         BigDecimal targetChannelFraction, BigDecimal adverseChannelFraction,
                         BigDecimal stopBufferAtr, BigDecimal maximumRiskToReward,
                         int maximumHoldingBars) {
        public Config {
            if (entryMode == null || levelLookback < 30 || pivotStrength < 1 || minimumConfirmations < 2
                    || atrPeriod < 2 || levelToleranceAtr.signum() <= 0
                    || entryToleranceAtr.signum() < 0 || minimumChannelWidthAtr.signum() <= 0
                    || targetChannelFraction.signum() <= 0
                    || targetChannelFraction.compareTo(BigDecimal.ONE) >= 0
                    || adverseChannelFraction.signum() <= 0
                    || adverseChannelFraction.compareTo(BigDecimal.ONE) >= 0
                    || stopBufferAtr.signum() < 0 || maximumRiskToReward.compareTo(BigDecimal.ONE) < 0
                    || maximumHoldingBars <= 0) {
                throw new IllegalArgumentException("Invalid three-level range configuration");
            }
        }
    }

    private record Cluster(BigDecimal sum, int count) {
        BigDecimal price() { return sum.divide(BigDecimal.valueOf(count), MC); }
        Cluster add(BigDecimal value) { return new Cluster(sum.add(value), count + 1); }
    }

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private final Config config;
    private final FeatureKey close = FeatureKey.close(), high = FeatureKey.high(), low = FeatureKey.low();
    private final FeatureKey atr;

    public ThreeLevelRangeStrategy(Config config) {
        this.config = config;
        atr = FeatureKey.atr(config.atrPeriod());
    }

    @Override public String name() { return "three-level-range-long"; }
    @Override public Set<FeatureKey> requiredFeatures() { return Set.of(close, high, low, atr); }

    @Override
    public StrategyDecision evaluate(List<FeatureSnapshot> history, int index, PositionView position) {
        if (position.isOpen()) {
            return position.barsHeld() >= config.maximumHoldingBars()
                    ? new StrategyDecision.Exit("three-level range time exit")
                    : StrategyDecision.hold();
        }
        if (index < config.levelLookback() + config.pivotStrength()) return StrategyDecision.hold();
        FeatureSnapshot current = history.get(index), previous = history.get(index - 1);
        BigDecimal a = current.require(atr);
        if (a.signum() <= 0) return StrategyDecision.hold();
        List<BigDecimal> levels = levels(history, index,
                a.multiply(config.levelToleranceAtr(), MC));
        if (levels.size() < 3) return StrategyDecision.hold();

        BigDecimal entryTolerance = a.multiply(config.entryToleranceAtr(), MC);
        for (int middle = 1; middle < levels.size() - 1; middle++) {
            BigDecimal level1 = levels.get(middle - 1);
            BigDecimal level2 = levels.get(middle);
            BigDecimal level3 = levels.get(middle + 1);
            BigDecimal channel = level3.subtract(level2, MC);
            if (channel.compareTo(a.multiply(config.minimumChannelWidthAtr(), MC)) < 0) continue;
            BigDecimal lowerChannel = level2.subtract(level1, MC);
            BigDecimal scratchTrigger = level2.subtract(
                    lowerChannel.multiply(config.adverseChannelFraction(), MC), MC);
            boolean rejection = (config.entryMode() == EntryMode.RECLAIM
                    ? current.require(low).compareTo(scratchTrigger) <= 0
                    : current.require(low).compareTo(level2.add(entryTolerance, MC)) <= 0)
                    && current.require(close).compareTo(level2) > 0
                    && current.require(close).compareTo(previous.require(close)) > 0
                    && current.require(close).compareTo(level3) < 0;
            if (!rejection) continue;

            BigDecimal target = level2.add(channel.multiply(config.targetChannelFraction(), MC), MC);
            BigDecimal stop = level1.subtract(a.multiply(config.stopBufferAtr(), MC), MC);
            BigDecimal reward = target.subtract(current.require(close), MC);
            BigDecimal risk = current.require(close).subtract(stop, MC);
            if (reward.signum() <= 0 || risk.signum() <= 0
                    || risk.compareTo(reward.multiply(config.maximumRiskToReward(), MC)) > 0) continue;
            return config.entryMode() == EntryMode.RECLAIM
                    ? new StrategyDecision.EnterAtLevels(Side.LONG, stop, target)
                    : new StrategyDecision.EnterAtLevelsWithScratch(
                    Side.LONG, stop, target, scratchTrigger);
        }
        return StrategyDecision.hold();
    }

    private List<BigDecimal> levels(List<FeatureSnapshot> h, int index, BigDecimal tolerance) {
        List<BigDecimal> candidates = new ArrayList<>();
        int from = index - config.levelLookback();
        for (int i = from + config.pivotStrength(); i < index - config.pivotStrength(); i++) {
            if (pivot(h, i, true)) candidates.add(h.get(i).require(high));
            if (pivot(h, i, false)) candidates.add(h.get(i).require(low));
        }
        candidates.sort(Comparator.naturalOrder());
        List<Cluster> clusters = new ArrayList<>();
        for (BigDecimal candidate : candidates) {
            if (clusters.isEmpty() || candidate.subtract(clusters.getLast().price(), MC).abs()
                    .compareTo(tolerance) > 0) {
                clusters.add(new Cluster(candidate, 1));
            } else {
                clusters.set(clusters.size() - 1, clusters.getLast().add(candidate));
            }
        }
        return clusters.stream().filter(cluster -> cluster.count() >= config.minimumConfirmations())
                .map(Cluster::price).toList();
    }

    private boolean pivot(List<FeatureSnapshot> h, int index, boolean upper) {
        BigDecimal value = h.get(index).require(upper ? high : low);
        for (int distance = 1; distance <= config.pivotStrength(); distance++) {
            BigDecimal left = h.get(index - distance).require(upper ? high : low);
            BigDecimal right = h.get(index + distance).require(upper ? high : low);
            if (upper ? value.compareTo(left) < 0 || value.compareTo(right) < 0
                    : value.compareTo(left) > 0 || value.compareTo(right) > 0) return false;
        }
        return true;
    }
}
