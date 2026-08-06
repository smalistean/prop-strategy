package com.smalistean.propstrategy.strategy;

public final class EmaPullbackStrategyFactory implements StrategyFactory {

    @Override
    public String type() {
        return "ema-pullback";
    }

    @Override
    public Strategy create(StrategyParameters parameters) {
        return new EmaPullbackStrategy(new EmaPullbackStrategy.Config(
                parameters.requiredInt("fastEma"),
                parameters.requiredInt("slowEma"),
                parameters.requiredInt("rsiPeriod"),
                parameters.requiredInt("atrPeriod"),
                parameters.requiredDecimal("longRsiMin"),
                parameters.requiredDecimal("longRsiMax"),
                parameters.requiredDecimal("shortRsiMin"),
                parameters.requiredDecimal("shortRsiMax"),
                parameters.requiredDecimal("stopAtrMultiplier"),
                parameters.requiredDecimal("rewardRiskRatio"),
                parameters.requiredInt("maxHoldingBars")));
    }
}
