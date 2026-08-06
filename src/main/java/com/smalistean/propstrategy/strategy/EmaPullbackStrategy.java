package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

public final class EmaPullbackStrategy implements Strategy {

    public record Config(
            int fastEma,
            int slowEma,
            int rsiPeriod,
            int atrPeriod,
            BigDecimal longRsiMin,
            BigDecimal longRsiMax,
            BigDecimal shortRsiMin,
            BigDecimal shortRsiMax,
            BigDecimal stopAtrMultiplier,
            BigDecimal rewardRiskRatio,
            int maxHoldingBars
    ) {
        public Config {
            if (fastEma <= 0 || slowEma <= fastEma || rsiPeriod <= 0 || atrPeriod <= 0
                    || stopAtrMultiplier.signum() <= 0 || rewardRiskRatio.signum() <= 0
                    || maxHoldingBars <= 0) {
                throw new IllegalArgumentException("Invalid EMA pullback configuration");
            }
        }
    }

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private final Config config;
    private final FeatureKey close = FeatureKey.close();
    private final FeatureKey fastEma;
    private final FeatureKey slowEma;
    private final FeatureKey rsi;
    private final FeatureKey atr;

    public EmaPullbackStrategy(Config config) {
        this.config = config;
        this.fastEma = FeatureKey.ema(config.fastEma());
        this.slowEma = FeatureKey.ema(config.slowEma());
        this.rsi = FeatureKey.rsi(config.rsiPeriod());
        this.atr = FeatureKey.atr(config.atrPeriod());
    }

    @Override
    public String name() {
        return "ema-pullback";
    }

    @Override
    public Set<FeatureKey> requiredFeatures() {
        return Set.of(close, fastEma, slowEma, rsi, atr);
    }

    @Override
    public StrategyDecision evaluate(List<FeatureSnapshot> history, int index,
                                     PositionView position) {
        FeatureSnapshot current = history.get(index);
        FeatureSnapshot previous = index == 0 ? null : history.get(index - 1);

        if (position.isOpen()) {
            if (position.barsHeld() >= config.maxHoldingBars()) {
                return new StrategyDecision.Exit("maximum holding period");
            }
            boolean trendReversed = position.side() == Side.LONG
                    ? current.require(fastEma).compareTo(current.require(slowEma)) < 0
                    : current.require(fastEma).compareTo(current.require(slowEma)) > 0;
            return trendReversed
                    ? new StrategyDecision.Exit("EMA trend reversal")
                    : StrategyDecision.hold();
        }
        if (previous == null) {
            return StrategyDecision.hold();
        }

        BigDecimal currentClose = current.require(close);
        BigDecimal previousClose = previous.require(close);
        BigDecimal currentFast = current.require(fastEma);
        BigDecimal previousFast = previous.require(fastEma);
        BigDecimal currentSlow = current.require(slowEma);
        BigDecimal currentRsi = current.require(rsi);
        BigDecimal stopDistance = current.require(atr).multiply(config.stopAtrMultiplier(), MC);
        BigDecimal targetDistance = stopDistance.multiply(config.rewardRiskRatio(), MC);

        boolean longPullbackEnded = currentFast.compareTo(currentSlow) > 0
                && previousClose.compareTo(previousFast) <= 0
                && currentClose.compareTo(currentFast) > 0
                && between(currentRsi, config.longRsiMin(), config.longRsiMax());
        if (longPullbackEnded) {
            return new StrategyDecision.Enter(Side.LONG, stopDistance, targetDistance);
        }

        boolean shortPullbackEnded = currentFast.compareTo(currentSlow) < 0
                && previousClose.compareTo(previousFast) >= 0
                && currentClose.compareTo(currentFast) < 0
                && between(currentRsi, config.shortRsiMin(), config.shortRsiMax());
        return shortPullbackEnded
                ? new StrategyDecision.Enter(Side.SHORT, stopDistance, targetDistance)
                : StrategyDecision.hold();
    }

    private static boolean between(BigDecimal value, BigDecimal minimum, BigDecimal maximum) {
        return value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0;
    }
}
