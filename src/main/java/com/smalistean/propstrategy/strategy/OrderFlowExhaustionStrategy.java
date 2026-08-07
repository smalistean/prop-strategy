package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

public final class OrderFlowExhaustionStrategy implements Strategy {

    public record Config(int trendEmaPeriod, int atrPeriod,
                         BigDecimal minimumCoverage, BigDecimal minimumQuality,
                         BigDecimal maximumTrendDiscount,
                         BigDecimal maximum15mImbalance,
                         BigDecimal maximumLargeTradeImbalance,
                         BigDecimal minimumAbsorption, BigDecimal minimumExhaustion,
                         BigDecimal minimumDeltaAcceleration,
                         BigDecimal minimumDivergence,
                         BigDecimal minimum15mReturn, BigDecimal maximum15mReturn,
                         BigDecimal recoveredBuyImbalance,
                         BigDecimal renewedSellImbalance,
                         BigDecimal stopAtrMultiplier, BigDecimal rewardRiskRatio,
                         int maximumHoldingBars, int cooldownBars) {
        public Config {
            if (trendEmaPeriod <= 1 || atrPeriod <= 1 || minimumCoverage.signum() < 0
                    || minimumCoverage.compareTo(BigDecimal.ONE) > 0 || minimumQuality.signum() < 0
                    || minimumQuality.compareTo(BigDecimal.ONE) > 0 || maximumTrendDiscount.signum() < 0
                    || maximum15mImbalance.signum() >= 0 || maximumLargeTradeImbalance.signum() >= 0
                    || minimumAbsorption.signum() < 0 || minimumExhaustion.signum() < 0
                    || minimumDeltaAcceleration.signum() < 0 || minimum15mReturn.compareTo(maximum15mReturn) > 0
                    || recoveredBuyImbalance.signum() <= 0 || renewedSellImbalance.signum() >= 0
                    || stopAtrMultiplier.signum() <= 0 || rewardRiskRatio.signum() <= 0
                    || maximumHoldingBars <= 0 || cooldownBars < 0) {
                throw new IllegalArgumentException("Invalid order-flow exhaustion configuration");
            }
        }
    }

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private final Config config;
    private final FeatureKey close = FeatureKey.close();
    private final FeatureKey trendEma;
    private final FeatureKey atr;
    private int lastEntryIndex = Integer.MIN_VALUE / 2;

    public OrderFlowExhaustionStrategy(Config config) {
        this.config = config;
        trendEma = FeatureKey.ema(config.trendEmaPeriod());
        atr = FeatureKey.atr(config.atrPeriod());
    }

    @Override
    public String name() {
        return "order-flow-exhaustion";
    }

    @Override
    public Set<FeatureKey> requiredFeatures() {
        return Set.of(close, trendEma, atr, FeatureKey.orderFlowCoverage(240),
                FeatureKey.orderFlowQuality(240), FeatureKey.orderFlowImbalance(5),
                FeatureKey.orderFlowImbalance(15), FeatureKey.largeTradeImbalance(15),
                FeatureKey.deltaAcceleration(5, 60), FeatureKey.sellAbsorption(15),
                FeatureKey.sellExhaustion(5, 15), FeatureKey.priceFlowDivergence(15),
                FeatureKey.priceReturn(15));
    }

    @Override
    public StrategyDecision evaluate(List<FeatureSnapshot> history, int index,
                                     PositionView position) {
        FeatureSnapshot current = history.get(index);
        BigDecimal imbalance5 = current.require(FeatureKey.orderFlowImbalance(5));
        if (position.isOpen()) {
            if (position.barsHeld() >= config.maximumHoldingBars()) {
                return new StrategyDecision.Exit("order-flow maximum holding period");
            }
            if (imbalance5.compareTo(config.recoveredBuyImbalance()) >= 0) {
                return new StrategyDecision.Exit("aggressive buying recovered");
            }
            if (imbalance5.compareTo(config.renewedSellImbalance()) <= 0) {
                return new StrategyDecision.Exit("renewed aggressive selling");
            }
            return StrategyDecision.hold();
        }
        if (index - lastEntryIndex <= config.cooldownBars()
                || current.require(FeatureKey.orderFlowCoverage(240)).compareTo(config.minimumCoverage()) < 0
                || current.require(FeatureKey.orderFlowQuality(240)).compareTo(config.minimumQuality()) < 0) {
            return StrategyDecision.hold();
        }
        BigDecimal minimumTrendPrice = current.require(trendEma)
                .multiply(BigDecimal.ONE.subtract(config.maximumTrendDiscount(), MC), MC);
        BigDecimal return15 = current.require(FeatureKey.priceReturn(15));
        boolean setup = current.require(close).compareTo(minimumTrendPrice) >= 0
                && current.require(FeatureKey.orderFlowImbalance(15))
                .compareTo(config.maximum15mImbalance()) <= 0
                && current.require(FeatureKey.largeTradeImbalance(15))
                .compareTo(config.maximumLargeTradeImbalance()) <= 0
                && current.require(FeatureKey.sellAbsorption(15)).compareTo(config.minimumAbsorption()) >= 0
                && current.require(FeatureKey.sellExhaustion(5, 15)).compareTo(config.minimumExhaustion()) >= 0
                && current.require(FeatureKey.deltaAcceleration(5, 60))
                .compareTo(config.minimumDeltaAcceleration()) >= 0
                && current.require(FeatureKey.priceFlowDivergence(15))
                .compareTo(config.minimumDivergence()) >= 0
                && return15.compareTo(config.minimum15mReturn()) >= 0
                && return15.compareTo(config.maximum15mReturn()) <= 0;
        if (!setup) return StrategyDecision.hold();
        lastEntryIndex = index;
        BigDecimal stop = current.require(atr).multiply(config.stopAtrMultiplier(), MC);
        return new StrategyDecision.Enter(Side.LONG, stop,
                stop.multiply(config.rewardRiskRatio(), MC));
    }
}
