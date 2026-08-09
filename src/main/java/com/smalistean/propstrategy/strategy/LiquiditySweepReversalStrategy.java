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

/**
 * Causal price-and-volume proxy for a stop/liquidity sweep. It does not claim to observe
 * exchange liquidations: a pivot-cluster level is swept, reclaimed, then structurally confirmed.
 */
public final class LiquiditySweepReversalStrategy implements Strategy {
    public record Config(int levelLookback, int pivotStrength, int minimumTouches, int atrPeriod,
                         int volumePeriod, int localBreakBars, BigDecimal levelToleranceAtr,
                         BigDecimal minimumSweepAtr, BigDecimal minimumConfirmationVolumeRatio,
                         BigDecimal stopBufferAtr, BigDecimal minimumRewardRisk,
                         int maximumHoldingBars) {
        public Config {
            if (levelLookback < 20 || pivotStrength < 1 || minimumTouches < 2 || atrPeriod < 2
                    || volumePeriod < 2 || localBreakBars < 1 || levelToleranceAtr.signum() <= 0
                    || minimumSweepAtr.signum() <= 0 || minimumConfirmationVolumeRatio.signum() <= 0
                    || stopBufferAtr.signum() <= 0 || minimumRewardRisk.compareTo(BigDecimal.ONE) < 0
                    || maximumHoldingBars <= 0) throw new IllegalArgumentException("Invalid liquidity-sweep configuration");
        }
    }

    private record Pool(BigDecimal price, boolean resistance, int lastPivot) { }
    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private final Config config;
    private final FeatureKey open = FeatureKey.open();
    private final FeatureKey close = FeatureKey.close();
    private final FeatureKey high = FeatureKey.high();
    private final FeatureKey low = FeatureKey.low();
    private final FeatureKey atr;
    private final FeatureKey volumeRatio;

    public LiquiditySweepReversalStrategy(Config config) {
        this.config = config;
        atr = FeatureKey.atr(config.atrPeriod());
        volumeRatio = FeatureKey.volumeRatio(config.volumePeriod());
    }

    @Override public String name() { return "liquidity-sweep-reversal"; }
    @Override public Set<FeatureKey> requiredFeatures() {
        return Set.of(open, close, high, low, atr, volumeRatio);
    }

    @Override public StrategyDecision evaluate(List<FeatureSnapshot> h, int index, PositionView position) {
        if (position.isOpen()) return position.barsHeld() >= config.maximumHoldingBars()
                ? new StrategyDecision.Exit("liquidity-sweep holding period expired") : StrategyDecision.hold();
        int required = config.levelLookback() + config.pivotStrength() + config.localBreakBars() + 2;
        if (index < required) return StrategyDecision.hold();
        FeatureSnapshot confirm = h.get(index);
        FeatureSnapshot sweep = h.get(index - 1);
        BigDecimal currentAtr = confirm.require(atr);
        if (currentAtr.signum() <= 0 || confirm.require(volumeRatio)
                .compareTo(config.minimumConfirmationVolumeRatio()) < 0) return StrategyDecision.hold();
        List<Pool> pools = pools(h, index - 1, currentAtr.multiply(config.levelToleranceAtr(), MC));
        StrategyDecision longEntry = longEntry(h, index, sweep, confirm, currentAtr, pools);
        return longEntry instanceof StrategyDecision.EnterAtLevels ? longEntry
                : shortEntry(h, index, sweep, confirm, currentAtr, pools);
    }

    private StrategyDecision longEntry(List<FeatureSnapshot> h, int i, FeatureSnapshot sweep,
                                       FeatureSnapshot confirm, BigDecimal a, List<Pool> pools) {
        Pool support = pools.stream().filter(pool -> !pool.resistance()
                        && sweep.require(low).compareTo(pool.price().subtract(a.multiply(config.minimumSweepAtr(), MC), MC)) < 0
                        && notPreviouslySwept(h, pool, i - 1, a))
                .max(Comparator.comparing(Pool::price)).orElse(null);
        if (support == null || confirm.require(close).compareTo(support.price()) <= 0
                || confirm.require(close).compareTo(confirm.require(open)) <= 0
                || confirm.require(close).compareTo(priorHigh(h, i, config.localBreakBars())) <= 0) return StrategyDecision.hold();
        BigDecimal stop = sweep.require(low).subtract(a.multiply(config.stopBufferAtr(), MC), MC);
        Pool target = pools.stream().filter(Pool::resistance).filter(pool -> pool.price().compareTo(confirm.require(close)) > 0)
                .min(Comparator.comparing(Pool::price)).orElse(null);
        return entry(Side.LONG, confirm.require(close), stop, target);
    }

    private StrategyDecision shortEntry(List<FeatureSnapshot> h, int i, FeatureSnapshot sweep,
                                        FeatureSnapshot confirm, BigDecimal a, List<Pool> pools) {
        Pool resistance = pools.stream().filter(Pool::resistance
                        ).filter(pool -> sweep.require(high).compareTo(pool.price().add(a.multiply(config.minimumSweepAtr(), MC), MC)) > 0
                        && notPreviouslySwept(h, pool, i - 1, a))
                .min(Comparator.comparing(Pool::price)).orElse(null);
        if (resistance == null || confirm.require(close).compareTo(resistance.price()) >= 0
                || confirm.require(close).compareTo(confirm.require(open)) >= 0
                || confirm.require(close).compareTo(priorLow(h, i, config.localBreakBars())) >= 0) return StrategyDecision.hold();
        BigDecimal stop = sweep.require(high).add(a.multiply(config.stopBufferAtr(), MC), MC);
        Pool target = pools.stream().filter(pool -> !pool.resistance()).filter(pool -> pool.price().compareTo(confirm.require(close)) < 0)
                .max(Comparator.comparing(Pool::price)).orElse(null);
        return entry(Side.SHORT, confirm.require(close), stop, target);
    }

    private StrategyDecision entry(Side side, BigDecimal price, BigDecimal stop, Pool target) {
        if (target == null) return StrategyDecision.hold();
        BigDecimal risk = side == Side.LONG ? price.subtract(stop, MC) : stop.subtract(price, MC);
        BigDecimal reward = side == Side.LONG ? target.price().subtract(price, MC) : price.subtract(target.price(), MC);
        if (risk.signum() <= 0 || reward.compareTo(risk.multiply(config.minimumRewardRisk(), MC)) < 0)
            return StrategyDecision.hold();
        return new StrategyDecision.EnterAtLevels(side, stop, target.price());
    }

    private List<Pool> pools(List<FeatureSnapshot> h, int end, BigDecimal tolerance) {
        int from = Math.max(config.pivotStrength(), end - config.levelLookback());
        List<Pool> result = new ArrayList<>();
        for (boolean resistance : List.of(true, false)) {
            for (int i = from; i <= end - config.pivotStrength(); i++) {
                if (!pivot(h, i, resistance)) continue;
                BigDecimal candidate = h.get(i).require(resistance ? high : low);
                int touches = 0;
                for (int j = from; j <= end - config.pivotStrength(); j++) {
                    if (h.get(j).require(resistance ? high : low).subtract(candidate, MC).abs()
                            .compareTo(tolerance) <= 0) touches++;
                }
                if (touches >= config.minimumTouches()) result.add(new Pool(candidate, resistance, i));
            }
        }
        return result;
    }

    private boolean pivot(List<FeatureSnapshot> h, int index, boolean resistance) {
        BigDecimal value = h.get(index).require(resistance ? high : low);
        for (int d = 1; d <= config.pivotStrength(); d++) {
            BigDecimal left = h.get(index - d).require(resistance ? high : low);
            BigDecimal right = h.get(index + d).require(resistance ? high : low);
            if (resistance ? value.compareTo(left) < 0 || value.compareTo(right) < 0
                    : value.compareTo(left) > 0 || value.compareTo(right) > 0) return false;
        }
        return true;
    }

    private boolean notPreviouslySwept(List<FeatureSnapshot> h, Pool pool, int sweepIndex, BigDecimal a) {
        BigDecimal threshold = a.multiply(config.minimumSweepAtr(), MC);
        for (int i = pool.lastPivot() + config.pivotStrength() + 1; i < sweepIndex; i++) {
            BigDecimal test = h.get(i).require(pool.resistance() ? high : low);
            if (pool.resistance() ? test.compareTo(pool.price().add(threshold, MC)) > 0
                    : test.compareTo(pool.price().subtract(threshold, MC)) < 0) return false;
        }
        return true;
    }

    private BigDecimal priorHigh(List<FeatureSnapshot> h, int index, int bars) {
        BigDecimal result = h.get(index - bars - 1).require(high);
        for (int i = index - bars; i < index; i++) result = result.max(h.get(i).require(high));
        return result;
    }
    private BigDecimal priorLow(List<FeatureSnapshot> h, int index, int bars) {
        BigDecimal result = h.get(index - bars - 1).require(low);
        for (int i = index - bars; i < index; i++) result = result.min(h.get(i).require(low));
        return result;
    }
}
