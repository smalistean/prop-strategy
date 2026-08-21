package com.smalistean.propstrategy.strategy.gerchik;

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
 * Mechanical interpretation of the course's BSU/BPU level-reaction setups.
 * The class deliberately exposes every numerical interpretation as configuration.
 */
public final class GerchikLevelStrategy implements Strategy {

    public enum Reaction { BOUNCE, BREAKOUT, FALSE_BREAKOUT }

    public record Config(Reaction reaction, int levelLookback, int atrPeriod,
                         int pivotStrength, int minimumConfirmations,
                         BigDecimal levelToleranceAtr, BigDecimal approachAtr,
                         int approachBars, BigDecimal maximumApproachOverlap,
                         BigDecimal breakoutAtr, BigDecimal stopBufferAtr,
                         BigDecimal minimumRewardRisk, BigDecimal targetAtr,
                         int maximumHoldingBars) {
        public Config {
            if (reaction == null || levelLookback < 20 || atrPeriod < 2 || pivotStrength < 1
                    || minimumConfirmations < 1 || levelToleranceAtr.signum() <= 0
                    || approachAtr.signum() <= 0 || approachBars < 2
                    || maximumApproachOverlap.signum() < 0
                    || maximumApproachOverlap.compareTo(BigDecimal.ONE) > 0
                    || breakoutAtr.signum() < 0 || stopBufferAtr.signum() <= 0
                    || minimumRewardRisk.compareTo(BigDecimal.ONE) < 0
                    || targetAtr.signum() <= 0 || maximumHoldingBars <= 0) {
                throw new IllegalArgumentException("Invalid Gerchik level strategy configuration");
            }
        }
    }

    private record Level(BigDecimal price, boolean resistance, int confirmations) {}

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private final Config config;
    private final FeatureKey close = FeatureKey.close();
    private final FeatureKey high = FeatureKey.high();
    private final FeatureKey low = FeatureKey.low();
    private final FeatureKey atr;

    public GerchikLevelStrategy(Config config) {
        this.config = config;
        atr = FeatureKey.atr(config.atrPeriod());
    }

    @Override public String name() {
        return "gerchik-level-" + config.reaction().name().toLowerCase().replace('_', '-');
    }

    @Override public Set<FeatureKey> requiredFeatures() { return Set.of(close, high, low, atr); }

    @Override
    public StrategyDecision evaluate(List<FeatureSnapshot> history, int index, PositionView position) {
        if (position.isOpen()) {
            return position.barsHeld() >= config.maximumHoldingBars()
                    ? new StrategyDecision.Exit("level setup holding period expired")
                    : StrategyDecision.hold();
        }
        int warmup = config.levelLookback() + config.pivotStrength();
        if (index < warmup) return StrategyDecision.hold();

        BigDecimal currentAtr = history.get(index).require(atr);
        if (currentAtr.signum() <= 0) return StrategyDecision.hold();
        BigDecimal tolerance = currentAtr.multiply(config.levelToleranceAtr(), MC);
        Level resistance = findLevel(history, index, true, tolerance);
        Level support = findLevel(history, index, false, tolerance);

        return switch (config.reaction()) {
            case BOUNCE -> bounce(history, index, currentAtr, resistance, support);
            case BREAKOUT -> breakout(history, index, currentAtr, resistance, support);
            case FALSE_BREAKOUT -> falseBreakout(history, index, currentAtr, resistance, support);
        };
    }

    private StrategyDecision bounce(List<FeatureSnapshot> h, int i, BigDecimal a,
                                    Level resistance, Level support) {
        FeatureSnapshot c = h.get(i);
        FeatureSnapshot p = h.get(i - 1);
        BigDecimal zone = a.multiply(config.approachAtr(), MC);
        if (support != null && p.require(low).compareTo(support.price().add(zone, MC)) <= 0
                && c.require(close).compareTo(support.price()) > 0
                && c.require(close).compareTo(p.require(close)) > 0
                && cleanApproach(h, i, Side.LONG)) {
            return entry(Side.LONG, c.require(close),
                    support.price().subtract(a.multiply(config.stopBufferAtr(), MC), MC), a);
        }
        if (resistance != null && p.require(high).compareTo(resistance.price().subtract(zone, MC)) >= 0
                && c.require(close).compareTo(resistance.price()) < 0
                && c.require(close).compareTo(p.require(close)) < 0
                && cleanApproach(h, i, Side.SHORT)) {
            return entry(Side.SHORT, c.require(close),
                    resistance.price().add(a.multiply(config.stopBufferAtr(), MC), MC), a);
        }
        return StrategyDecision.hold();
    }

    private StrategyDecision breakout(List<FeatureSnapshot> h, int i, BigDecimal a,
                                      Level resistance, Level support) {
        FeatureSnapshot c = h.get(i);
        FeatureSnapshot p = h.get(i - 1);
        BigDecimal threshold = a.multiply(config.breakoutAtr(), MC);
        if (resistance != null && p.require(close).compareTo(resistance.price()) <= 0
                && c.require(close).compareTo(resistance.price().add(threshold, MC)) > 0
                && compressedApproach(h, i, resistance.price(), a)) {
            return entry(Side.LONG, c.require(close),
                    resistance.price().subtract(a.multiply(config.stopBufferAtr(), MC), MC), a);
        }
        if (support != null && p.require(close).compareTo(support.price()) >= 0
                && c.require(close).compareTo(support.price().subtract(threshold, MC)) < 0
                && compressedApproach(h, i, support.price(), a)) {
            return entry(Side.SHORT, c.require(close),
                    support.price().add(a.multiply(config.stopBufferAtr(), MC), MC), a);
        }
        return StrategyDecision.hold();
    }

    private StrategyDecision falseBreakout(List<FeatureSnapshot> h, int i, BigDecimal a,
                                           Level resistance, Level support) {
        FeatureSnapshot c = h.get(i);
        FeatureSnapshot p = h.get(i - 1);
        BigDecimal threshold = a.multiply(config.breakoutAtr(), MC);
        if (support != null && p.require(low).compareTo(support.price().subtract(threshold, MC)) < 0
                && c.require(close).compareTo(support.price()) > 0) {
            BigDecimal extreme = p.require(low).min(c.require(low));
            return entry(Side.LONG, c.require(close),
                    extreme.subtract(a.multiply(config.stopBufferAtr(), MC), MC), a);
        }
        if (resistance != null && p.require(high).compareTo(resistance.price().add(threshold, MC)) > 0
                && c.require(close).compareTo(resistance.price()) < 0) {
            BigDecimal extreme = p.require(high).max(c.require(high));
            return entry(Side.SHORT, c.require(close),
                    extreme.add(a.multiply(config.stopBufferAtr(), MC), MC), a);
        }
        return StrategyDecision.hold();
    }

    private StrategyDecision entry(Side side, BigDecimal price, BigDecimal stop, BigDecimal a) {
        BigDecimal risk = side == Side.LONG ? price.subtract(stop, MC) : stop.subtract(price, MC);
        if (risk.signum() <= 0) return StrategyDecision.hold();
        BigDecimal reward = a.multiply(config.targetAtr(), MC)
                .max(risk.multiply(config.minimumRewardRisk(), MC));
        BigDecimal target = side == Side.LONG ? price.add(reward, MC) : price.subtract(reward, MC);
        if (target.signum() <= 0) return StrategyDecision.hold();
        return new StrategyDecision.EnterAtLevels(side, stop, target);
    }

    private Level findLevel(List<FeatureSnapshot> h, int index, boolean resistance,
                            BigDecimal tolerance) {
        int from = index - config.levelLookback();
        int pivotEnd = index - config.approachBars();
        BigDecimal currentPrice = h.get(index).require(close);
        Level best = null;
        for (int i = from + config.pivotStrength(); i < pivotEnd - config.pivotStrength(); i++) {
            BigDecimal candidate = h.get(i).require(resistance ? high : low);
            if (!isPivot(h, i, resistance)) continue;
            int confirmations = 0;
            for (int j = from; j < pivotEnd; j++) {
                BigDecimal value = h.get(j).require(resistance ? high : low);
                if (value.subtract(candidate, MC).abs().compareTo(tolerance) <= 0) confirmations++;
            }
            if (confirmations >= config.minimumConfirmations()
                    && (best == null || candidate.subtract(currentPrice, MC).abs()
                    .compareTo(best.price().subtract(currentPrice, MC).abs()) < 0)) {
                best = new Level(candidate, resistance, confirmations);
            }
        }
        return best;
    }

    private boolean isPivot(List<FeatureSnapshot> h, int i, boolean resistance) {
        BigDecimal value = h.get(i).require(resistance ? high : low);
        for (int d = 1; d <= config.pivotStrength(); d++) {
            BigDecimal left = h.get(i - d).require(resistance ? high : low);
            BigDecimal right = h.get(i + d).require(resistance ? high : low);
            if (resistance ? value.compareTo(left) < 0 || value.compareTo(right) < 0
                    : value.compareTo(left) > 0 || value.compareTo(right) > 0) return false;
        }
        return true;
    }

    private boolean cleanApproach(List<FeatureSnapshot> h, int i, Side side) {
        int favorable = 0;
        int overlaps = 0;
        int from = i - config.approachBars();
        for (int j = from + 1; j < i; j++) {
            BigDecimal previous = h.get(j - 1).require(close);
            BigDecimal current = h.get(j).require(close);
            if (side == Side.LONG ? current.compareTo(previous) < 0 : current.compareTo(previous) > 0)
                favorable++;
            if (h.get(j).require(low).compareTo(h.get(j - 1).require(high)) <= 0
                    && h.get(j).require(high).compareTo(h.get(j - 1).require(low)) >= 0) overlaps++;
        }
        int transitions = config.approachBars() - 1;
        BigDecimal overlapRatio = BigDecimal.valueOf(overlaps)
                .divide(BigDecimal.valueOf(transitions), MC);
        return favorable * 2 >= transitions
                && overlapRatio.compareTo(config.maximumApproachOverlap()) <= 0;
    }

    private boolean compressedApproach(List<FeatureSnapshot> h, int i, BigDecimal level,
                                       BigDecimal currentAtr) {
        int from = i - config.approachBars();
        BigDecimal firstDistance = h.get(from).require(close).subtract(level, MC).abs();
        BigDecimal lastDistance = h.get(i - 1).require(close).subtract(level, MC).abs();
        BigDecimal maxRange = BigDecimal.ZERO;
        BigDecimal minRange = null;
        for (int j = from; j < i; j++) {
            BigDecimal range = h.get(j).require(high).subtract(h.get(j).require(low), MC);
            maxRange = maxRange.max(range);
            minRange = minRange == null ? range : minRange.min(range);
        }
        return lastDistance.compareTo(firstDistance) < 0
                && minRange != null
                && maxRange.compareTo(currentAtr.multiply(config.approachAtr(), MC)) <= 0;
    }
}
