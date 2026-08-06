package com.smalistean.propstrategy.strategy;

public final class VolatilityCompressionBreakoutStrategyFactory implements StrategyFactory {

    @Override
    public String type() {
        return "volatility-compression-breakout";
    }

    @Override
    public Strategy create(StrategyParameters parameters) {
        return new VolatilityCompressionBreakoutStrategy(
                new VolatilityCompressionBreakoutStrategy.Config(
                        parameters.requiredInt("compressionLookback"),
                        parameters.requiredInt("rangePeriod"),
                        parameters.requiredDecimal("maximumBandwidthPercentile"),
                        parameters.requiredInt("atrPeriod"),
                        parameters.requiredDecimal("minimumAtrExpansionRatio"),
                        parameters.requiredInt("volumePeriod"),
                        parameters.requiredDecimal("minimumVolumeRatio"),
                        parameters.requiredDecimal("stopAtrMultiplier"),
                        parameters.requiredDecimal("rewardRiskRatio"),
                        parameters.requiredInt("maxHoldingBars")));
    }
}
