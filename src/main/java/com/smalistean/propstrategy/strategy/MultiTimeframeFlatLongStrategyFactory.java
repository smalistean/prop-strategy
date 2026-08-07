package com.smalistean.propstrategy.strategy;

public final class MultiTimeframeFlatLongStrategyFactory implements StrategyFactory {
    @Override
    public String type() {
        return "multi-timeframe-flat-long";
    }

    @Override
    public Strategy create(StrategyParameters parameters) {
        return new MultiTimeframeFlatLongStrategy(new MultiTimeframeFlatLongStrategy.Config(
                parameters.requiredDecimal("maximumRegimeMovePercent"),
                parameters.requiredDecimal("entryRsi"),
                parameters.requiredDecimal("exitRsi"),
                parameters.requiredDecimal("maximumAtrExpansionRatio"),
                parameters.requiredDecimal("stopAtrMultiplier"),
                parameters.requiredDecimal("rewardRiskRatio"),
                parameters.requiredInt("setupLifetime5mBars"),
                parameters.requiredInt("maxHolding5mBars")));
    }
}
