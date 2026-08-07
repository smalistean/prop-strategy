package com.smalistean.propstrategy.strategy;

public final class OrderFlowExhaustionStrategyFactory implements StrategyFactory {

    @Override
    public String type() {
        return "order-flow-exhaustion";
    }

    @Override
    public Strategy create(StrategyParameters parameters) {
        return new OrderFlowExhaustionStrategy(new OrderFlowExhaustionStrategy.Config(
                parameters.requiredInt("trendEmaPeriod"), parameters.requiredInt("atrPeriod"),
                parameters.requiredDecimal("minimumCoverage"), parameters.requiredDecimal("minimumQuality"),
                parameters.requiredDecimal("maximumTrendDiscount"),
                parameters.requiredDecimal("maximum15mImbalance"),
                parameters.requiredDecimal("maximumLargeTradeImbalance"),
                parameters.requiredDecimal("minimumAbsorption"),
                parameters.requiredDecimal("minimumExhaustion"),
                parameters.requiredDecimal("minimumDeltaAcceleration"),
                parameters.requiredDecimal("minimumDivergence"),
                parameters.requiredDecimal("minimum15mReturn"),
                parameters.requiredDecimal("maximum15mReturn"),
                parameters.requiredDecimal("recoveredBuyImbalance"),
                parameters.requiredDecimal("renewedSellImbalance"),
                parameters.requiredDecimal("stopAtrMultiplier"),
                parameters.requiredDecimal("rewardRiskRatio"),
                parameters.requiredInt("maximumHoldingBars"), parameters.requiredInt("cooldownBars")));
    }
}
