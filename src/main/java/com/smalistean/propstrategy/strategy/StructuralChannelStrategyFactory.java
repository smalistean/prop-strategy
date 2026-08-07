package com.smalistean.propstrategy.strategy;

public final class StructuralChannelStrategyFactory implements StrategyFactory {

    @Override
    public String type() {
        return "structural-channel";
    }

    @Override
    public Strategy create(StrategyParameters parameters) {
        return new StructuralChannelStrategy(new StructuralChannelStrategy.Config(
                parameters.requiredInt("channelPeriod"), parameters.requiredInt("atrPeriod"),
                parameters.requiredInt("minimumTouches"),
                parameters.requiredDecimal("touchToleranceAtr"),
                parameters.requiredDecimal("entryZoneAtr"),
                parameters.requiredDecimal("stopBufferAtr"),
                parameters.requiredDecimal("targetBufferAtr"),
                parameters.requiredDecimal("minimumChannelRiskMultiple"),
                parameters.requiredDecimal("minimumRewardRisk"),
                parameters.requiredInt("maximumHoldingBars")));
    }
}
