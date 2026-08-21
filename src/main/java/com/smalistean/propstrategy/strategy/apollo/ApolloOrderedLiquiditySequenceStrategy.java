package com.smalistean.propstrategy.strategy.apollo;

import com.smalistean.propstrategy.strategy.PositionView;
import com.smalistean.propstrategy.strategy.Side;
import com.smalistean.propstrategy.strategy.Strategy;
import com.smalistean.propstrategy.strategy.StrategyDecision;
import com.smalistean.propstrategy.strategy.StrategyFactory;
import com.smalistean.propstrategy.strategy.StrategyParameters;
import com.smalistean.propstrategy.strategy.VolumeProfileAwareStrategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

/**
 * Strict, causal Apollo proxy: a fresh mapped area must be swept, reclaimed, and then followed by
 * a separate local structural break. This deliberately does not treat a POC/level touch as entry.
 */
public final class ApolloOrderedLiquiditySequenceStrategy implements Strategy {
    public record Config(int atrPeriod, int volumePeriod, int freshnessBars, int reclaimWindowBars,
                         int minimumAcceptanceBars, int localBreakBars, int sweepSearchBars, BigDecimal sweepAtr,
                         BigDecimal levelBufferAtr, BigDecimal minimumBodyAtr,
                         BigDecimal minConfirmationVolumeRatio, BigDecimal stopBufferAtr,
                         BigDecimal minRewardRisk, int maxHoldingBars, boolean higherTimeframeAlignment) { }

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private final Config config;
    private final FeatureKey atr;
    private final FeatureKey volume;

    public ApolloOrderedLiquiditySequenceStrategy(Config config) {
        this.config = config;
        atr = FeatureKey.atr(config.atrPeriod());
        volume = FeatureKey.volumeRatio(config.volumePeriod());
    }

    @Override
    public String name() {
        return "apollo-ordered-liquidity-sequence-v3";
    }

    @Override
    public Set<FeatureKey> requiredFeatures() {
        return Set.of(FeatureKey.open(), FeatureKey.close(), FeatureKey.high(), FeatureKey.low(), atr,
                volume, FeatureKey.higherTimeframeSupport(), FeatureKey.higherTimeframeResistance());
    }

    @Override
    public StrategyDecision evaluate(List<FeatureSnapshot> history, int index, PositionView position) {
        if (position.isOpen()) {
            return position.barsHeld() >= config.maxHoldingBars()
                    ? new StrategyDecision.Exit("maximum holding period") : StrategyDecision.hold();
        }
        int required = config.freshnessBars() + config.reclaimWindowBars() + config.localBreakBars() + 2;
        if (index < required) return StrategyDecision.hold();

        FeatureSnapshot current = history.get(index);
        BigDecimal currentAtr = current.require(atr);
        if (currentAtr.signum() <= 0
                || current.require(volume).compareTo(config.minConfirmationVolumeRatio()) < 0) {
            return StrategyDecision.hold();
        }
        BigDecimal support = current.require(FeatureKey.higherTimeframeSupport());
        BigDecimal resistance = current.require(FeatureKey.higherTimeframeResistance());
        if (support.signum() > 0 && resistance.signum() > 0) {
            StrategyDecision longEntry = longEntry(history, index, current, currentAtr, support, resistance);
            if (!longEntry.equals(StrategyDecision.hold())) return longEntry;
            StrategyDecision shortEntry = shortEntry(history, index, current, currentAtr, support, resistance);
            if (!shortEntry.equals(StrategyDecision.hold())) return shortEntry;
        }
        return StrategyDecision.hold();
    }

    private StrategyDecision longEntry(List<FeatureSnapshot> history, int index, FeatureSnapshot current,
                                       BigDecimal currentAtr, BigDecimal support, BigDecimal target) {
        if (config.higherTimeframeAlignment() && current.require(FeatureKey.completedFourHourClose())
                .compareTo(current.require(FeatureKey.completedFourHourEma(50))) <= 0) return StrategyDecision.hold();
        int sweep = mostRecentSweep(history, index, support, currentAtr, true);
        if (sweep < 0 || !freshBeforeSweep(history, sweep, support, currentAtr, true)
                || !reclaimed(history, sweep, index, support, true)
                || !breaksLocalStructure(history, index, true)) return StrategyDecision.hold();
        BigDecimal stop = history.get(sweep).require(FeatureKey.low())
                .subtract(currentAtr.multiply(config.stopBufferAtr(), MC), MC);
        return entry(Side.LONG, current.require(FeatureKey.close()), stop, target);
    }

    private StrategyDecision shortEntry(List<FeatureSnapshot> history, int index, FeatureSnapshot current,
                                        BigDecimal currentAtr, BigDecimal resistance, BigDecimal target) {
        if (config.higherTimeframeAlignment() && current.require(FeatureKey.completedFourHourClose())
                .compareTo(current.require(FeatureKey.completedFourHourEma(50))) >= 0) return StrategyDecision.hold();
        int sweep = mostRecentSweep(history, index, resistance, currentAtr, false);
        if (sweep < 0 || !freshBeforeSweep(history, sweep, resistance, currentAtr, false)
                || !reclaimed(history, sweep, index, resistance, false)
                || !breaksLocalStructure(history, index, false)) return StrategyDecision.hold();
        BigDecimal stop = history.get(sweep).require(FeatureKey.high())
                .add(currentAtr.multiply(config.stopBufferAtr(), MC), MC);
        return entry(Side.SHORT, current.require(FeatureKey.close()), stop, target);
    }

    private int mostRecentSweep(List<FeatureSnapshot> history, int index, BigDecimal level,
                                BigDecimal currentAtr, boolean longSide) {
        BigDecimal threshold = currentAtr.multiply(config.sweepAtr(), MC);
        int from = Math.max(0, index - config.sweepSearchBars());
        // The first breach starts the sequence. A later wick during the reclaim must not be
        // misclassified as a separate, non-fresh sweep.
        for (int i = from; i < index; i++) {
            BigDecimal extreme = history.get(i).require(longSide ? FeatureKey.low() : FeatureKey.high());
            boolean swept = longSide ? extreme.compareTo(level.subtract(threshold, MC)) <= 0
                    : extreme.compareTo(level.add(threshold, MC)) >= 0;
            if (swept) return i;
        }
        return -1;
    }

    private boolean freshBeforeSweep(List<FeatureSnapshot> history, int sweep, BigDecimal level,
                                     BigDecimal currentAtr, boolean longSide) {
        BigDecimal buffer = currentAtr.multiply(config.levelBufferAtr(), MC);
        int from = Math.max(0, sweep - config.freshnessBars());
        for (int i = from; i < sweep; i++) {
            BigDecimal extreme = history.get(i).require(longSide ? FeatureKey.low() : FeatureKey.high());
            boolean alreadyVisited = longSide ? extreme.compareTo(level.add(buffer, MC)) <= 0
                    : extreme.compareTo(level.subtract(buffer, MC)) >= 0;
            if (alreadyVisited) return false;
        }
        return true;
    }

    private boolean reclaimed(List<FeatureSnapshot> history, int sweep, int index,
                              BigDecimal level, boolean longSide) {
        int latest = Math.min(index - 1, sweep + config.reclaimWindowBars());
        int consecutive = 0;
        for (int i = sweep + 1; i <= latest; i++) {
            FeatureSnapshot bar = history.get(i);
            BigDecimal close = bar.require(FeatureKey.close());
            boolean reclaimed = longSide ? close.compareTo(level) > 0 : close.compareTo(level) < 0;
            boolean directionalBody = longSide
                    ? close.compareTo(bar.require(FeatureKey.open())) > 0
                    : close.compareTo(bar.require(FeatureKey.open())) < 0;
            BigDecimal body = close.subtract(bar.require(FeatureKey.open()), MC).abs();
            boolean fullBody = body.compareTo(bar.require(atr)
                    .multiply(config.minimumBodyAtr(), MC)) >= 0;
            if (reclaimed && directionalBody && fullBody) {
                consecutive++;
                if (consecutive >= config.minimumAcceptanceBars()) return true;
            } else consecutive = 0;
        }
        return false;
    }

    private boolean breaksLocalStructure(List<FeatureSnapshot> history, int index, boolean longSide) {
        FeatureSnapshot current = history.get(index);
        BigDecimal boundary = longSide ? history.get(index - config.localBreakBars()).require(FeatureKey.high())
                : history.get(index - config.localBreakBars()).require(FeatureKey.low());
        for (int i = index - config.localBreakBars() + 1; i < index; i++) {
            BigDecimal candidate = history.get(i).require(longSide ? FeatureKey.high() : FeatureKey.low());
            boundary = longSide ? boundary.max(candidate) : boundary.min(candidate);
        }
        return longSide ? current.require(FeatureKey.close()).compareTo(boundary) > 0
                : current.require(FeatureKey.close()).compareTo(boundary) < 0;
    }

    private StrategyDecision entry(Side side, BigDecimal price, BigDecimal stop, BigDecimal target) {
        BigDecimal risk = side == Side.LONG ? price.subtract(stop, MC) : stop.subtract(price, MC);
        BigDecimal reward = side == Side.LONG ? target.subtract(price, MC) : price.subtract(target, MC);
        return risk.signum() > 0 && reward.compareTo(risk.multiply(config.minRewardRisk(), MC)) >= 0
                ? new StrategyDecision.EnterAtLevels(side, stop, target) : StrategyDecision.hold();
    }
}
