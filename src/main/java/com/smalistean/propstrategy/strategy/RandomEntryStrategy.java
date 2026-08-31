package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Enters at random, and exists only to be beaten.
 *
 * <p>A strategy that passes the challenge in 40% of simulated attempts sounds like an edge until a
 * coin flip trading at the same rate, with the same stop and target geometry and the same position
 * sizing, also passes 40% of the time. Then the 40% belongs to the payoff structure and the market's
 * drift, not to the strategy, and ranking by it selects noise. This is the same control the XVF work
 * used when ranked candidate selection had to beat random selection on two independent years before
 * its signal was believed.
 *
 * <p>Everything except the entry decision is deliberately shared with the strategy under test:
 * stop and target come from the same ATR feature and the same multiples, so the only difference
 * being measured is <i>when to enter and which way</i>. {@code entryProbabilityPerBar} should be set
 * from the tested strategy's own observed trade rate rather than chosen, otherwise the comparison
 * comes down to trade frequency instead of trade quality.
 */
public final class RandomEntryStrategy implements Strategy {

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);

    private final long seed;
    private final double entryProbabilityPerBar;
    private final BigDecimal stopAtrMultiplier;
    private final BigDecimal rewardRiskRatio;
    private final int maxHoldingBars;
    private final FeatureKey atr;
    private final Random random;

    public RandomEntryStrategy(long seed, double entryProbabilityPerBar,
                               BigDecimal stopAtrMultiplier, BigDecimal rewardRiskRatio,
                               int maxHoldingBars, int atrPeriod) {
        if (entryProbabilityPerBar <= 0 || entryProbabilityPerBar > 1) {
            throw new IllegalArgumentException("entryProbabilityPerBar must be in (0, 1]");
        }
        if (stopAtrMultiplier.signum() <= 0 || rewardRiskRatio.signum() <= 0 || maxHoldingBars <= 0) {
            throw new IllegalArgumentException("Invalid random-entry configuration");
        }
        this.seed = seed;
        this.entryProbabilityPerBar = entryProbabilityPerBar;
        this.stopAtrMultiplier = stopAtrMultiplier;
        this.rewardRiskRatio = rewardRiskRatio;
        this.maxHoldingBars = maxHoldingBars;
        this.atr = FeatureKey.atr(atrPeriod);
        this.random = new Random(seed);
    }

    @Override
    public String name() {
        return "random-entry(seed=%d)".formatted(seed);
    }

    @Override
    public Set<FeatureKey> requiredFeatures() {
        return Set.of(FeatureKey.close(), atr);
    }

    @Override
    public StrategyDecision evaluate(List<FeatureSnapshot> history, int index,
                                     PositionView position) {
        if (position.isOpen()) {
            return position.barsHeld() >= maxHoldingBars
                    ? new StrategyDecision.Exit("maximum holding period")
                    : StrategyDecision.hold();
        }
        if (random.nextDouble() >= entryProbabilityPerBar) {
            return StrategyDecision.hold();
        }
        BigDecimal stopDistance = history.get(index).require(atr).multiply(stopAtrMultiplier, MC);
        return new StrategyDecision.Enter(
                random.nextBoolean() ? Side.LONG : Side.SHORT,
                stopDistance, stopDistance.multiply(rewardRiskRatio, MC));
    }
}
