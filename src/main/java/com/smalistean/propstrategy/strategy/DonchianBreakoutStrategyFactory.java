package com.smalistean.propstrategy.strategy;

public final class DonchianBreakoutStrategyFactory implements StrategyFactory {

    @Override
    public String type() {
        return "donchian-breakout";
    }

    @Override
    public Strategy create(StrategyParameters parameters) {
        return new DonchianBreakoutStrategy(new DonchianBreakoutStrategy.Config(
                parameters.requiredInt("entryLookback"),
                parameters.requiredInt("exitLookback"),
                parameters.requiredInt("volumePeriod"),
                parameters.requiredDecimal("minimumVolumeRatio"),
                parameters.requiredInt("atrPeriod"),
                parameters.requiredDecimal("stopAtrMultiplier"),
                parameters.requiredDecimal("rewardRiskRatio"),
                parameters.requiredInt("maxHoldingBars")));
    }
}
